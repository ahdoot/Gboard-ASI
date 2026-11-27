"""Build Gboard ASI 0.20 from the signed 0.19 APK and its existing voice engine."""
import argparse
import hashlib
from pathlib import Path
import struct
import sys
import tempfile
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from update_apk import package_update, run

BASE_SHA = "bb1575f62f823c05cef333f28c9d9b692852e90caefc598abddacf04fef656ff"
VOICE = "Llocal/gboard/asi/AsiVoice;"
EDITOR = "Landroid/view/inputmethod/InputConnection;"


def patch_voice(source):
    start = source.index(".method startFromTouch()V")
    end = source.index(".end method", start)
    method = source[start:end]
    editor_begin = method.index("    invoke-interface {v1}, " + EDITOR + "->finishComposingText")
    generation_begin = method.index("    iget v1, p0, " + VOICE + "->generation:I", editor_begin)
    routing_begin = method.index("    invoke-direct {p0}, " + VOICE + "->language()", generation_begin)
    routing_end = method.index("    :cond_a\n", routing_begin)

    editor = method[editor_begin:generation_begin]
    generation = method[generation_begin:routing_begin]
    routing = method[routing_begin:routing_end]

    # Both providers deliver transcripts on the IME main thread. Start the worker/service
    # first, then finish setup in this same main-thread callback before a result is handled.
    # Starting can fail synchronously; current(id) also rejects a replaced or hidden editor.
    launch = f"""    move v5, v1
    invoke-direct {{p0, v5}}, {VOICE}->startCapture(I)V
    invoke-direct {{p0, v5}}, {VOICE}->current(I)Z
    move-result v2
    if-eqz v2, :cond_a

    iget-object v1, p0, {VOICE}->editor:{EDITOR}
    :try_start_editor
"""
    # A broken editor must not leave the microphone recording after a failed setup.
    # Check the session again before publishing the cursor snapshot.
    editor = editor.replace("    :cond_3\n", f"""    :cond_3
    :try_end_editor
    .catch Ljava/lang/RuntimeException; {{:try_start_editor .. :try_end_editor}} :editor_failed
    invoke-direct {{p0, v5}}, {VOICE}->current(I)Z
    move-result v0
    if-eqz v0, :cond_a
""")
    cleanup = f"""    goto :cond_a
    :editor_failed
    move-exception v0
    invoke-direct {{p0, v5}}, {VOICE}->current(I)Z
    move-result v0
    if-eqz v0, :cond_a
    invoke-direct {{p0}}, {VOICE}->end()V
"""
    capture = (".method private startCapture(I)V\n    .locals 7\n    move v1, p1\n"
               + routing + "    return-void\n.end method\n\n")
    startup = method[:editor_begin] + generation + launch + editor + cleanup + method[routing_end:]
    return source[:start] + capture + startup + source[end:]


def updated_manifest(data):
    for old, new in [(struct.pack("<HBBI", 8, 0, 16, 176071788),
                      struct.pack("<HBBI", 8, 0, 16, 176071789)),
                     ("18.6.3-asi0.19".encode("utf-16le"), "18.6.3-asi0.20".encode("utf-16le"))]:
        if data.count(old) != 1:
            raise ValueError("Unexpected version in the 0.19 manifest")
        data = data.replace(old, new)
    return data


def build(args):
    with args.base.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != BASE_SHA:
            raise ValueError("Expected the original signed Gboard-ASI-0.19.apk")
    if args.out.exists():
        raise ValueError("Choose a new output path")
    with tempfile.TemporaryDirectory() as temp, zipfile.ZipFile(args.base) as base:
        work = Path(temp)
        mini = work / "voice.zip"
        with zipfile.ZipFile(mini, "w") as z:
            z.writestr("classes.dex", base.read("classes6.dex"))
        apktool = ["java", "-Xmx1100m", "-jar", args.apktool]
        decoded = work / "decoded"
        run(*apktool, "d", "-r", "-j", "2", "-o", decoded, mini)
        voice = decoded / "smali/local/gboard/asi/AsiVoice.smali"
        voice.write_text(patch_voice(voice.read_text()))
        patched = work / "updated.zip"
        run(*apktool, "b", "-j", "2", "-o", patched, decoded)
        package_update(args, work, base, patched, updated_manifest(base.read("AndroidManifest.xml")),
                       {"classes6.dex": "classes.dex"})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("base", "out", "tools", "apktool", "keystore"):
        parser.add_argument("--" + name, type=Path, required=True)
    build(parser.parse_args())
