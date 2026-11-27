"""Add English spoken punctuation to live and final transcripts in signed Gboard ASI 0.20."""
import argparse
import hashlib
from pathlib import Path
import struct
import sys
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from update_apk import compile_bridge, package_update, run

BASE_SHA = "d7bff301fc510df15c2ad0fc185e780966e5ea54883968e9ac1154c3bae4aedf"
HOOK = "Llocal/gboard/asi/SpokenPunctuation;->format(Ljava/lang/String;)Ljava/lang/String;"


def once(content, old, new):
    if content.count(old) != 1:
        raise ValueError("Unexpected contents in the pinned 0.20 release")
    return content.replace(old, new, 1)


def patch_phrase(source):
    # Both live preview and final commit pass through replace; format before spacing and offsets.
    target = ".method private replace(Ljava/lang/String;Z)Z\n    .locals 5\n"
    return once(source, target, target + f"\n    invoke-static {{p1}}, {HOOK}\n    move-result-object p1\n")


def build(args):
    with args.base.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != BASE_SHA:
            raise ValueError("Expected the original signed Gboard-ASI-0.20.apk")
    if args.out.exists():
        raise ValueError("Choose a new output path")
    with tempfile.TemporaryDirectory() as temp, zipfile.ZipFile(args.base) as base:
        work = Path(temp)
        manifest = once(base.read("AndroidManifest.xml"), struct.pack("<HBBI", 8, 0, 16, 176071789),
                        struct.pack("<HBBI", 8, 0, 16, 176071790))
        manifest = once(manifest, "18.6.3-asi0.20".encode("utf-16le"), "18.6.3-asi0.21".encode("utf-16le"))
        if "classes10.dex" in base.namelist():
            raise ValueError("Occupied voice bridge DEX slot")
        mini = work / "voice.zip"
        with zipfile.ZipFile(mini, "w") as z:
            z.writestr("classes.dex", base.read("classes6.dex"))
        apktool = ["java", "-Xmx1100m", "-jar", args.apktool]
        decoded = work / "decoded"
        run(*apktool, "d", "-r", "-j", "2", "-o", decoded, mini)
        phrase = decoded / "smali/local/gboard/asi/DictatedText.smali"
        phrase.write_text(patch_phrase(phrase.read_text()))
        patched = work / "updated.zip"
        run(*apktool, "b", "-j", "2", "-o", patched, decoded)
        compile_bridge(args, work, HERE / "SpokenPunctuation.java")
        package_update(args, work, base, patched, manifest, {"classes6.dex": "classes.dex"}, "classes10.dex")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("base", "out", "tools", "apktool", "android-jar", "keystore"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--ecj", type=Path)
    build(parser.parse_args())
