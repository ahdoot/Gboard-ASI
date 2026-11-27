"""Build ASI 0.17 from the signed 0.16 APK."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import struct
import sys
import tempfile
import zipfile

ASI = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ASI))
from update_apk import compile_bridge, package_update, run
from word_strip.build import compile_strip

BASE_SHA = "cca1b61a7e7da94ad285ed8843e6d97591897983eb6816fc74eb0e8e4d80cd5d"
MIC = Path("local/gboard/asi/MicIndicator.smali")


def tighten_word_style(resources):
    # Only the native word-label style references this pair. Other consumers
    # of the 6dp / 8dp dimension resources retain their original sizes.
    def attribute(key, kind, value):
        return struct.pack("<IHBBI", key, 8, 0, kind, value)
    left, right = 0x010100D6, 0x010100D8
    before = attribute(left, 1, 0x7F0708E2) + attribute(right, 1, 0x7F0708E3)
    after = attribute(left, 5, 0x301) + attribute(right, 5, 0x301)
    if resources.count(before) != 1:
        raise ValueError("Unexpected word-label padding")
    return resources.replace(before, after)


def update_hit_test(path):
    text = path.read_text()
    start = text.index(".method private hit(Landroid/view/View;FF)Z")
    end = text.index(".end method", start)
    method = text[start:end]
    # Keep the guards for disabled/hidden keys and other IME windows. Reuse the
    # same Rect and coordinate array to hit-test the transformed button on screen.
    start_math = method.index("    iget-object v1, p0, Llocal/gboard/asi/MicTouch;->bounds:")
    end_math = method.index("    :cond_1", start_math)
    original_math = method[start_math:end_math]
    if "->getLocalVisibleRect(" not in original_math or "->getLocationOnScreen(" not in original_math:
        raise ValueError("Unsupported microphone hit test")
    converted = """    iget-object v1, p0, Llocal/gboard/asi/MicTouch;->bounds:Landroid/graphics/Rect;
    iget-object v2, p0, Llocal/gboard/asi/MicTouch;->position:[I
    invoke-static {p1, p2, p3, v1, v2}, Llocal/gboard/asi/MicIndicator;->containsScreenPoint(Landroid/view/View;FFLandroid/graphics/Rect;[I)Z
    move-result v0
    return v0

"""
    path.write_text(text[:start] + method[:start_math] + converted + method[end_math:] + text[end:])


def build(args):
    with args.base.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != BASE_SHA:
            raise ValueError("Expected the signed 0.16 keyboard")
    if args.out.exists() or not os.environ.get("ASI_KS_PASS"):
        raise ValueError("Choose a new output path and set ASI_KS_PASS")
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(args.base) as base:
        work = Path(tmp)
        mic, strip = work / "mic", work / "strip"
        mic.mkdir()
        strip.mkdir()
        compile_bridge(args, mic, ASI / "MicIndicator.java")
        compile_strip(args, strip)
        with zipfile.ZipFile(work / "input.zip", "w") as parts:
            parts.writestr("classes.dex", base.read("classes6.dex"))
            parts.writestr("classes2.dex", base.read("classes2.dex"))
            parts.write(mic / "classes.dex", "classes3.dex")
        decoded = work / "decoded"
        apktool = ["java", "-jar", args.apktool]
        run(*apktool, "d", "-r", "-j", "2", "-o", decoded, work / "input.zip")
        fresh = decoded / "smali_classes3"
        if list(fresh.rglob("*.smali")) != [fresh / MIC]:
            raise ValueError("Unexpected classes in compiled indicator")
        shutil.copyfile(fresh / MIC, decoded / "smali" / MIC)
        shutil.rmtree(fresh)
        update_hit_test(decoded / "smali/local/gboard/asi/MicTouch.smali")
        holder, = decoded.rglob("LatinFixedCountCandidatesHolderView.smali")
        code = holder.read_text()
        if " onMeasure(II)V" in code or ".super Landroid/widget/LinearLayout;" not in code:
            raise ValueError("Unexpected candidate holder")
        holder.write_text(code + """
.method protected onMeasure(II)V
    .locals 0
    invoke-super {p0, p1, p2}, Landroid/widget/LinearLayout;->onMeasure(II)V
    invoke-static {p0}, LAsiWordStrip;->compactWords(Landroid/widget/LinearLayout;)V
    return-void
.end method
""")
        compiled = work / "voice.zip"
        run(*apktool, "b", "-j", "2", "-o", compiled, decoded)
        with zipfile.ZipFile(compiled, "a") as out:
            out.write(strip / "classes.dex", "classes9.dex")
            out.writestr("resources.arsc", tighten_word_style(base.read("resources.arsc")))
        manifest = base.read("AndroidManifest.xml")
        for old, new in [(struct.pack("<HBBI", 8, 0, 16, 176071785), struct.pack("<HBBI", 8, 0, 16, 176071786)),
                         ("18.6.3-asi0.16".encode("utf-16le"), "18.6.3-asi0.17".encode("utf-16le"))]:
            if manifest.count(old) != 1:
                raise ValueError("Unexpected base manifest")
            manifest = manifest.replace(old, new)
        package_update(args, work, base, compiled, manifest,
                       {"classes6.dex": "classes.dex", "classes2.dex": "classes2.dex",
                        "classes9.dex": "classes9.dex", "resources.arsc": "resources.arsc"})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("base", "tools", "android-jar", "apktool", "keystore", "out"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--ecj", type=Path)
    build(parser.parse_args())
