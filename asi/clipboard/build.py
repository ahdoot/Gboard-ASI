"""Build Gboard ASI 0.13 from the signed 0.12 keyboard."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile

BASE_SHA = "0440cd907bbe92760b02cf90ab0e5ddbe9f9ee5cf924a27dd66967277a89eaa8"
CERT = "c5ab9ecbb3a5e97a36cfc3c2d0e0716b6cd7e66849733fa44e2b66dfb4db2306"
BRIDGE = "Llocal/gboard/asi/clipboard/GmailImagePaste;"
HERE = Path(__file__).resolve().parent


def once(value, old, new):
    if value.count(old) != 1:
        raise ValueError("Unexpected contents in pinned 0.12 input")
    return value.replace(old, new, 1)


def hook_ime(smali):
    method = ".method public final onStartInputView(Landroid/view/inputmethod/EditorInfo;Z)V"
    return re.sub(re.escape(method) + r"\n    \.locals \d+\n",
                  lambda m: m[0] + f"    invoke-static/range {{p0 .. p0}}, {BRIDGE}->bind(Landroid/inputmethodservice/InputMethodService;)V\n",
                  smali, count=1)


def hook_paste(smali):
    # v6..v8 are the exact editor, image URI and MIME passed to the standard commit path.
    start = smali.index("    invoke-static/range {v4 .. v9}, Lkqe;->w(")
    prefix, rest = smali[:start], smali[start:]
    method, tail = rest.split(".end method", 1)
    # Branch only on the "unsupported" status, before the original unsupported banner.
    instructions = re.sub(r"(?m)^\s*\.line \d+\s*$", "", method)
    match = re.search(r"move-result p0\s+add-int/lit8 p0, p0, -0x1\s+if-eqz p0, (:\w+)", instructions)
    if not match:
        raise ValueError("Expected clipboard result dispatch")
    target = "\n    " + match[1] + "\n"
    method = once(method, target, target + f"""    invoke-static/range {{v6 .. v8}}, {BRIDGE}->paste(Landroid/view/inputmethod/EditorInfo;Landroid/net/Uri;Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :asi_original_image_warning
    return v0
    :asi_original_image_warning
""")
    return prefix + method + ".end method" + tail


def patch_manifest(data):
    old = struct.pack("<HBBI", 8, 0, 0x10, 176071781)
    new = struct.pack("<HBBI", 8, 0, 0x10, 176071782)
    data = once(data, old, new)
    before, after = "18.6.3-asi0.12", "18.6.3-asi0.13"
    for encoding in ("utf-8", "utf-16le"):
        if before.encode(encoding) in data:
            return once(data, before.encode(encoding), after.encode(encoding))
    raise ValueError("Missing version name")


def run(*args):
    subprocess.run(list(map(str, args)), check=True)


def build(a):
    with a.base.open("rb") as f:
        if hashlib.file_digest(f, "sha256").hexdigest() != BASE_SHA:
            raise ValueError("Expected the original signed Gboard-ASI-0.12.apk")
    if a.out.exists():
        raise ValueError("Output already exists")
    if not os.environ.get("ASI_KS_PASS"):
        raise ValueError("Set ASI_KS_PASS")
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(a.base) as base:
        work = Path(tmp)
        mini = work / "base.zip"
        with zipfile.ZipFile(mini, "w") as z:
            for n, src in (("classes.dex", "classes2.dex"), ("classes2.dex", "classes3.dex")):
                z.writestr(n, base.read(src))
        decoded = work / "decoded"
        apktool = ["java", "-Xmx1400m", "-jar", a.apktool]
        run(*apktool, "d", "-j", "2", "-r", "-o", decoded, mini)
        for filename, patch in (("smali/pbb.smali", hook_ime), ("smali_classes2/fmx.smali", hook_paste)):
            path = decoded / filename
            original = path.read_text()
            updated = patch(original)
            if original == updated:
                raise ValueError("Missing hook in " + filename)
            path.write_text(updated)
        patched = work / "patched.zip"
        run(*apktool, "b", "-j", "2", "-o", patched, decoded)

        classes = work / "classes"
        classes.mkdir()
        compiler = ["java", "-jar", a.ecj] if a.ecj else ["javac"]
        run(*compiler, "-source", "8", "-target", "8", "-proc:none", "-classpath", a.android_jar,
            "-d", classes, HERE / "GmailImagePaste.java")
        jar = work / "bridge.jar"
        with zipfile.ZipFile(jar, "w") as z:
            for item in classes.rglob("*.class"):
                z.write(item, item.relative_to(classes))
        run(a.tools / "d8", "--min-api", "29", "--lib", a.android_jar, "--output", work, jar)
        unsigned = work / "unsigned.apk"
        with zipfile.ZipFile(unsigned, "w") as out, zipfile.ZipFile(patched) as dex:
            for entry in base.infolist():
                name = entry.filename
                if re.fullmatch(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:RSA|DSA|EC|SF))", name, re.I):
                    continue
                if name == "AndroidManifest.xml":
                    out.writestr(entry, patch_manifest(base.read(entry)))
                elif name in ("classes2.dex", "classes3.dex"):
                    out.writestr(entry, dex.read("classes.dex" if name == "classes2.dex" else "classes2.dex"))
                else:
                    with base.open(entry) as src, out.open(entry, "w") as dst:
                        shutil.copyfileobj(src, dst, 1024 * 1024)
            out.write(work / "classes.dex", "classes7.dex", zipfile.ZIP_DEFLATED)
        aligned, signed = work / "aligned.apk", work / "signed.apk"
        run(a.tools / "zipalign", "-P", "16", "-f", "4", unsigned, aligned)
        run(a.tools / "apksigner", "sign", "--ks", a.keystore, "--ks-key-alias", "asi",
            "--ks-pass", "env:ASI_KS_PASS", "--v4-signing-enabled", "false", "--out", signed, aligned)
        result = subprocess.check_output([a.tools / "apksigner", "verify", "--print-certs", signed], text=True)
        if "certificate SHA-256 digest: " + CERT not in result:
            raise ValueError("Signing identity did not match 0.12")
        run(a.tools / "zipalign", "-c", "-P", "16", "4", signed)
        a.out.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(signed, a.out)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for argument in ("base", "tools", "android-jar", "apktool", "keystore", "out"):
        parser.add_argument("--" + argument, required=True, type=Path)
    parser.add_argument("--ecj", type=Path)
    build(parser.parse_args())
