"""Build the DND-aware 0.14 in-place update from the signed 0.13 keyboard."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import struct
import sys
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from update_apk import compile_bridge, package_update, run

BASE_SHA = "b586014f3d8a91a079bf9975e9b174bb20f17e0f491cf34ceaf2e0b0c7d4aa3d"
BRIDGE = "Llocal/gboard/asi/haptics/DndHaptics;"
# Only the four dex files with live haptic call sites need reassembly.
DEX = {"classes.dex": "classes.dex", "classes2.dex": "classes2.dex",
       "classes3.dex": "classes3.dex", "classes6.dex": "classes4.dex"}
EXT = "smali/com/akshaykadam/pixelboard/extension"
VIEW_CALLS = {
    f"{EXT}/keyboard/GboardKeyboardRuntime.smali": 2,
    "smali_classes3/com/google/android/libraries/inputmethod/lens/selection/ui/TextSelectionView.smali": 1,
    "smali_classes3/mb.smali": 1,
    "smali_classes3/qpt.smali": 1,
    "smali_classes4/local/gboard/asi/MicTouch.smali": 1,
}


def patch_views(root):
    instruction = re.compile(r"invoke-virtual(/range)? (\{[^}]+\}), L[^;]+;->performHapticFeedback\((II?)\)Z")
    for filename, expected in VIEW_CALLS.items():
        path = root / filename
        patched, count = instruction.subn(
            rf"invoke-static\1 \2, {BRIDGE}->feedback(Landroid/view/View;\3)Z", path.read_text())
        if count != expected:
            raise ValueError(f"Expected {expected} feedback calls in {filename}, got {count}")
        path.write_text(patched)


def guard_vibrator(path, method, context):
    # The pinned methods already have a scratch local v0 available on entry.
    original = path.read_text()
    header = re.compile(re.escape(method) + r"\n    \.locals [1-9]\d*\n")
    guard = (context + f"    invoke-static {{v0}}, {BRIDGE}->allowed(Landroid/content/Context;)Z\n"
             "    move-result v0\n    if-nez v0, :asi_haptics_enabled\n"
             "    return-void\n    :asi_haptics_enabled\n")
    updated, count = header.subn(lambda match: match[0] + guard, original)
    if count != 1:
        raise ValueError(f"Expected a single {method} in {path.name}")
    path.write_text(updated)


def manifest_version(data):
    old = struct.pack("<HBBI", 8, 0, 0x10, 176071782)
    if data.count(old) != 1:
        raise ValueError("Unexpected version code")
    data = data.replace(old, struct.pack("<HBBI", 8, 0, 0x10, 176071783), 1)
    for encoding in ("utf-8", "utf-16le"):
        old = "18.6.3-asi0.13".encode(encoding)
        if data.count(old) == 1:
            return data.replace(old, "18.6.3-asi0.14".encode(encoding), 1)
    raise ValueError("Unexpected version name")


def build(args):
    with args.base.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != BASE_SHA:
            raise ValueError("Expected the original signed Gboard-ASI-0.13.apk")
    if args.out.exists() or not os.environ.get("ASI_KS_PASS"):
        raise ValueError("Choose a new output path and set ASI_KS_PASS for the ASI signing key")
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(args.base) as base:
        work = Path(tmp)
        mini = work / "base.zip"
        with zipfile.ZipFile(mini, "w") as z:
            for source, dest in DEX.items():
                z.writestr(dest, base.read(source))
        root = work / "decoded"
        apktool = ["java", "-Xmx1800m", "-jar", args.apktool]
        run(*apktool, "d", "-j", "2", "-r", "-o", root, mini)
        patch_views(root)
        guard_vibrator(root / "smali_classes2/pny.smali", ".method public final f(I)V",
                       "    iget-object v0, p0, Lpny;->h:Landroid/content/Context;\n")
        guard_vibrator(root / EXT / "settings/GboardHapticHelper.smali",
                       ".method public static vibratePreview(Landroid/content/Context;I)V",
                       "    move-object v0, p0\n")
        patched = work / "patched.zip"
        run(*apktool, "b", "-j", "2", "-o", patched, root)
        compile_bridge(args, work, HERE / "DndHaptics.java")
        package_update(args, work, base, patched, manifest_version(base.read("AndroidManifest.xml")),
                       DEX, "classes8.dex")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("base", "tools", "android-jar", "apktool", "keystore", "out"):
        parser.add_argument("--" + key, required=True, type=Path)
    parser.add_argument("--ecj", type=Path)
    build(parser.parse_args())
