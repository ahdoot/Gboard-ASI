"""Add the three-day clipboard option to the signed Gboard ASI 0.18 release."""
import argparse
import hashlib
from pathlib import Path
import shutil
import struct
import sys
import tempfile
import zipfile

ASI = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ASI))
from update_apk import package_update, run

SOURCE = ASI.parent / "pixelboard-patches/extensions/extension/src/main/java"
PACKAGE = Path("com/akshaykadam/pixelboard/extension/clipboard")
CLASSES = ("GboardClipboardHistorySettings", "GboardClipboardHistoryRuntime",
           "GboardClipboardHistorySettingsFeature")
BASE_HASH = "84231d420b97979cc0716bf478f4b02d95200ced6c77e6f4f16fe34f657058b7"


def updated_manifest(data):
    for old, new in ((struct.pack("<HBBI", 8, 0, 16, 176071787),
                      struct.pack("<HBBI", 8, 0, 16, 176071788)),
                     ("18.6.3-asi0.18".encode("utf-16le"), "18.6.3-asi0.19".encode("utf-16le"))):
        if data.count(old) != 1:
            raise ValueError("Unexpected base manifest")
        data = data.replace(old, new)
    return data


def compile_changes(args, work):
    classes = work / "classes"
    classes.mkdir()
    compiler = ["java", "-jar", args.ecj] if args.ecj else ["javac"]
    run(*compiler, "-source", "8", "-target", "8", "-proc:none", "-sourcepath", SOURCE,
        "-classpath", args.android_jar, "-d", classes,
        *(SOURCE / PACKAGE / (name + ".java") for name in CLASSES))
    changed = work / "changed.jar"
    dependencies = work / "dependencies.jar"
    with zipfile.ZipFile(changed, "w") as patch, zipfile.ZipFile(dependencies, "w") as deps:
        for path in sorted(classes.rglob("*.class")):
            relative = path.relative_to(classes)
            target = patch if relative.parent == PACKAGE else deps
            target.write(path, relative)
    dex = work / "changes.zip"
    run(args.tools / "d8", "--release", "--min-api", "29", "--lib", args.android_jar,
        "--classpath", dependencies, "--output", dex, changed)
    return dex


def build(args):
    with args.base.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != BASE_HASH:
            raise ValueError("Expected the original signed Gboard-ASI-0.18-rollback.apk")
    if args.out.exists():
        raise ValueError("Choose a new output file")
    with tempfile.TemporaryDirectory() as temp, zipfile.ZipFile(args.base) as base:
        work = Path(temp)
        changes = compile_changes(args, work)
        mini = work / "input.zip"
        with zipfile.ZipFile(mini, "w") as z:
            z.writestr("classes.dex", base.read("classes.dex"))
        apktool = ["java", "-Xmx1500m", "-jar", args.apktool]
        target, overlay = work / "base", work / "overlay"
        for source, dest in ((mini, target), (changes, overlay)):
            run(*apktool, "d", "-r", "-j", "2", "-o", dest, source)
        # Swap the three rebuilt classes together with their compiler-generated lambdas.
        # All other classes and APK entries come from the signed rollback release.
        target_package = target / "smali" / PACKAGE
        overlay_package = overlay / "smali" / PACKAGE
        for name in CLASSES:
            if not (overlay_package / (name + ".smali")).is_file():
                raise ValueError("Missing rebuilt class " + name)
            for old in target_package.glob(name + "$*.smali"):
                old.unlink()
        shutil.copytree(overlay_package, target_package, dirs_exist_ok=True)
        patched = work / "patched.zip"
        run(*apktool, "b", "-j", "2", "-o", patched, target)
        package_update(args, work, base, patched, updated_manifest(base.read("AndroidManifest.xml")),
                       {"classes.dex": "classes.dex"})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("base", "out", "tools", "android-jar", "apktool", "keystore"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--ecj", type=Path)
    build(parser.parse_args())
