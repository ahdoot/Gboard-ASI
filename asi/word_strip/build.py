"""Build the word-only, compact ASI 0.16 suggestion strip from the signed 0.15 APK."""
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
from update_apk import package_update, run

BASE_SHA = "45cc01d43b4fe52d95ae6f31dd3ccf0ed4d406346480cc43c43f18f4b53a6361"
DEX = {name: name for name in ("classes.dex", "classes2.dex", "classes3.dex")}
RUNTIME = "com/akshaykadam/pixelboard/extension/writingtools/GboardAiWritingToolsRuntime"


def replace_once(path, pattern, replacement):
    source = path.read_text()
    if len(re.findall(pattern, source)) != 1:
        raise ValueError(f"Unexpected hook in {path.name}: {pattern}")
    path.write_text(re.sub(pattern, lambda m: replacement(m), source))


def patch(root):
    replace_once(root / "smali/rcq.smali",
                 r"(?ms)(^\.method public static ah\(Lqou;\)Z\n    \.locals 2\n).*?^\.end method",
                 lambda m: m[1] + "\n    const/4 v0, 0x0\n    return v0\n.end method")
    runtime = root / f"smali/{RUNTIME}.smali"
    replace_once(runtime, r"->readFlagName\(Ljava/lang/Object;\)Ljava/lang/String;\s+move-result-object p0",
                 lambda m: m[0] + """
    invoke-static {p0}, LAsiWordStrip;->blockedFlag(Ljava/lang/String;)Z
    move-result v1
    if-eqz v1, :asi_regular_flag
    instance-of v1, p1, Ljava/lang/Boolean;
    if-eqz v1, :asi_regular_flag
    sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
    return-object v0
    :asi_regular_flag
""")
    replace_once(runtime, r"->getDefaultSharedPreferences\(Landroid/content/Context;\)Landroid/content/SharedPreferences;\s+move-result-object v0",
                 lambda m: m[0] + "\n    invoke-static {v0}, LAsiWordStrip;->disableEmojiPrefs(Landroid/content/SharedPreferences;)V\n")
    # Filter the normal keyboard batch before it is counted or split between the
    # strip and expanded rows. The explicit emoji/search keyboard owns its results.
    for name in ("LatinPrimeKeyboard", "PrimeKeyboard", "PageablePrimeKeyboard",
                 "LatinDigitsKeyboard", "LatinSymbolsKeyboard", "AccessoryKeyboard",
                 "JapanesePrimeKeyboard", "PinyinHardKeyboard"):
        path, = root.rglob(name + ".smali")
        replace_once(path, r"(?m)^\.method public (?:final )?c\(Ljava/util/List;Loqg;Z\)V\n    \.locals \d+",
                     lambda m: m[0] + """
    invoke-static/range {p1 .. p1}, LAsiWordStrip;->words(Ljava/util/List;)Ljava/util/List;
    move-result-object p1
    invoke-static/range {p2 .. p2}, LAsiWordStrip;->wordOrNull(Loqg;)Loqg;
    move-result-object p2
""")
    label, = root.rglob("AutoCenterScaleTextView.smali")
    # The native routine has just calculated the glyph bounds (v3), padded label
    # bounds (v1) and horizontal translation (v10). The original matrix handles
    # scaling, rendering and hit testing for compact and wide layouts.
    replace_once(label, r"(?m)^    sub-float/2addr v10, v11$",
                 lambda m: m[0] + """
    invoke-static {v0, v1, v3, v10}, LAsiWordStrip;->align(Landroid/view/View;Landroid/graphics/RectF;Landroid/graphics/RectF;F)F
    move-result v10
""")


def compile_strip(args, work):
    classes = work / "classes"
    classes.mkdir()
    compiler = ["java", "-jar", args.ecj] if args.ecj else ["javac"]
    run(*compiler, "-source", "8", "-target", "8", "-proc:none", "-classpath", args.android_jar,
        "-d", classes, HERE / "AsiWordStrip.java", HERE / "CandidateAbi.java")
    bridge = work / "strip.jar"
    with zipfile.ZipFile(bridge, "w") as jar:
        jar.write(classes / "AsiWordStrip.class", "AsiWordStrip.class")
    run(args.tools / "d8", "--min-api", "29", "--lib", args.android_jar, "--output", work, bridge)


def build(args):
    with args.base.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != BASE_SHA:
            raise ValueError("Expected the original signed Gboard-ASI-0.15.apk")
    if args.out.exists() or not os.environ.get("ASI_KS_PASS"):
        raise ValueError("Choose a new output path and set ASI_KS_PASS for the existing signing key")
    with tempfile.TemporaryDirectory() as temp, zipfile.ZipFile(args.base) as base:
        work = Path(temp)
        manifest = base.read("AndroidManifest.xml")
        for old, new in [(struct.pack("<HBBI", 8, 0, 16, 176071784), struct.pack("<HBBI", 8, 0, 16, 176071785)),
                         ("18.6.3-asi0.15".encode("utf-16le"), "18.6.3-asi0.16".encode("utf-16le"))]:
            if manifest.count(old) != 1:
                raise ValueError("Unexpected base manifest")
            manifest = manifest.replace(old, new)
        with zipfile.ZipFile(work / "input.zip", "w") as mini:
            for name in DEX:
                mini.writestr(name, base.read(name))
        apktool = ["java", "-Xmx1500m", "-jar", args.apktool]
        root = work / "decoded"
        run(*apktool, "d", "-r", "-j", "3", "-o", root, work / "input.zip")
        patch(root)
        patched = work / "patched.zip"
        run(*apktool, "b", "-j", "3", "-o", patched, root)
        compile_strip(args, work)
        package_update(args, work, base, patched, manifest, DEX, "classes9.dex")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("base", "tools", "android-jar", "apktool", "keystore", "out"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--ecj", type=Path)
    build(parser.parse_args())
