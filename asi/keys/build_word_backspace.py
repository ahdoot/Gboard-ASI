"""Build Gboard ASI 0.22 with native word deletion on soft backspace repeats."""
import argparse
import hashlib
from pathlib import Path
import struct
import sys
import tempfile
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from update_apk import package_update, run

BASE_SHA = "6ca316029740e387247889a4d36ee9638e9b2f8045b1dafcc51fb7052c2b8896"
METADATA = "Lcom/google/android/libraries/inputmethod/metadata/"
ARGS = f"({METADATA}ActionDef;{METADATA}SoftKeyDef;JZJI"
TAP = f"q{ARGS})V"
DISPATCH = f"asiDispatchKey{ARGS}Z)V"


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError(f"Expected one patch point for {old!r}")
    return text.replace(old, new)


def patch_pointer(text):
    start = text.index(".method public final " + TAP + "\n")
    end = text.index(".end method", start) + len(".end method")
    body = text[start:end]
    # All existing callers keep the tap signature. Only the auto-repeat task sets
    # the extra argument. Keep the physical key and action on the pointer so key-up,
    # movement and cancellation still see the key that was actually touched.
    tap_wrapper = f""".method public final {TAP}
    .locals 10
    move-object/from16 v0, p0
    move-object/from16 v1, p1
    move-object/from16 v2, p2
    move-wide/from16 v3, p3
    move/from16 v5, p5
    move-wide/from16 v6, p6
    move/from16 v8, p8
    const/4 v9, 0x0
    invoke-virtual/range {{v0 .. v9}}, Lqbp;->{DISPATCH}
    return-void
.end method

"""
    body = replace_once(body, TAP, DISPATCH)
    save_key = "    iput v0, p0, Lqbp;->j:I"
    # This is the same DELETE_WORD payload used for hardware Ctrl+Backspace.
    body = replace_once(body, save_key, save_key + """
    move/from16 v10, p9
    if-eqz v10, :asi_keep_key
    const/16 v10, 0x43
    if-ne v0, v10, :asi_keep_key
    new-instance v3, Lpue;
    const/16 v10, -0x2795
    sget-object v11, Lpud;->c:Lpud;
    const/4 v12, 0x0
    const v13, 0x7fffffff
    invoke-direct {v3, v10, v11, v12, v13}, Lpue;-><init>(ILpud;Ljava/lang/Object;I)V
    :asi_keep_key""")
    return text[:start] + tap_wrapper + body + text[end:]


def patch_repeat(text):
    call = "    invoke-virtual/range {v0 .. v8}, Lqbp;->" + TAP
    text = replace_once(text, call, "    const/4 v9, 0x1\n"
                        "    invoke-virtual/range {v0 .. v9}, Lqbp;->" + DISPATCH)
    # Retain the configured hold delay. Pace subsequent word repeats at 200 ms
    # or the keyboard's existing interval if it is already longer.
    interval = text.index(f"    invoke-virtual {{p0}}, {METADATA}SoftKeyDef;->e()I", text.index(DISPATCH))
    end = text.index("    sget-object p0, Ljava/util/concurrent/TimeUnit;", interval)
    block = replace_once(text[interval:end], "    int-to-long v3, p0", """    iget v3, v0, Lqbp;->j:I
    const/16 v4, 0x43
    if-ne v3, v4, :asi_native_interval
    const/16 v3, 0xc8
    invoke-static {p0, v3}, Ljava/lang/Math;->max(II)I
    move-result p0
    :asi_native_interval
    int-to-long v3, p0""")
    return text[:interval] + block + text[end:]


def build(args):
    with args.base.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != BASE_SHA:
            raise ValueError("Expected the original signed Gboard-ASI-0.21.apk")
    if args.out.exists():
        raise ValueError("Choose a new output path")
    with tempfile.TemporaryDirectory() as temp, zipfile.ZipFile(args.base) as base:
        work = Path(temp)
        manifest = replace_once(base.read("AndroidManifest.xml"), struct.pack("<HBBI", 8, 0, 16, 176071790),
                                struct.pack("<HBBI", 8, 0, 16, 176071791))
        manifest = replace_once(manifest, "18.6.3-asi0.21".encode("utf-16le"), "18.6.3-asi0.22".encode("utf-16le"))
        mini, decoded, patched = work / "keys.zip", work / "decoded", work / "updated.zip"
        with zipfile.ZipFile(mini, "w") as dex:
            dex.writestr("classes.dex", base.read("classes3.dex"))
        apktool = ["java", "-Xmx1500m", "-jar", args.apktool]
        run(*apktool, "d", "-r", "-j", "2", "-o", decoded, mini)
        for name, patch in (("qbp", patch_pointer), ("pom", patch_repeat)):
            path = decoded / "smali" / (name + ".smali")
            path.write_text(patch(path.read_text()))
        run(*apktool, "b", "-j", "2", "-o", patched, decoded)
        package_update(args, work, base, patched, manifest, {"classes3.dex": "classes.dex"})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("base", "out", "tools", "apktool", "keystore"):
        parser.add_argument("--" + name, type=Path, required=True)
    build(parser.parse_args())
