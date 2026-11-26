"""Compile a Java bridge and preserve the base APK's entries and ASI signing identity."""
import re
import shutil
import subprocess
import zipfile

CERT = "c5ab9ecbb3a5e97a36cfc3c2d0e0716b6cd7e66849733fa44e2b66dfb4db2306"


def run(*args):
    subprocess.run(list(map(str, args)), check=True)


def compile_bridge(a, work, source):
    classes = work / "classes"
    classes.mkdir()
    compiler = ["java", "-jar", a.ecj] if a.ecj else ["javac"]
    run(*compiler, "-source", "8", "-target", "8", "-proc:none", "-classpath", a.android_jar,
        "-d", classes, source)
    jar = work / "bridge.jar"
    with zipfile.ZipFile(jar, "w") as z:
        for item in classes.rglob("*.class"):
            z.write(item, item.relative_to(classes))
    run(a.tools / "d8", "--min-api", "29", "--lib", a.android_jar, "--output", work, jar)


def package_update(a, work, base, patched, manifest, dex_mapping, bridge_name):
    unsigned = work / "unsigned.apk"
    with zipfile.ZipFile(unsigned, "w") as out, zipfile.ZipFile(patched) as dex:
        for entry in base.infolist():
            name = entry.filename
            if re.fullmatch(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:RSA|DSA|EC|SF))", name, re.I):
                continue
            if name == "AndroidManifest.xml":
                out.writestr(entry, manifest)
            elif name in dex_mapping:
                out.writestr(entry, dex.read(dex_mapping[name]))
            else:
                with base.open(entry) as src, out.open(entry, "w") as dst:
                    shutil.copyfileobj(src, dst, 1024 * 1024)
        out.write(work / "classes.dex", bridge_name, zipfile.ZIP_DEFLATED)
    aligned, signed = work / "aligned.apk", work / "signed.apk"
    run(a.tools / "zipalign", "-P", "16", "-f", "4", unsigned, aligned)
    run(a.tools / "apksigner", "sign", "--ks", a.keystore, "--ks-key-alias", "asi",
        "--ks-pass", "env:ASI_KS_PASS", "--v4-signing-enabled", "false", "--out", signed, aligned)
    result = subprocess.check_output([a.tools / "apksigner", "verify", "--print-certs", signed], text=True)
    if "certificate SHA-256 digest: " + CERT not in result:
        raise ValueError("Unexpected ASI signing identity")
    run(a.tools / "zipalign", "-c", "-P", "16", "4", signed)
    a.out.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(signed, a.out)
