"""Build ASI 0.15 from the signed 0.14 APK, restoring native candidate ordering."""
import argparse
import hashlib
import os
from pathlib import Path
import struct
import sys
import tempfile
import zipfile
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from update_apk import package_update

BASE_SHA = "fa73861adf6df8e6ce27439eae2149d2cdb55df48b792f389adbfc7a7aa4be27"


def restore_native_candidates(dex):
    # At entry to LatinFixedCountCandidatesHolderView.D(List, ArrayList), p1
    # (v27) already refers to Gboard's original list. Disable only the injected
    # invoke-static/range and move-result-object. NOPs preserve all code offsets.
    offset = 0x4F57D0
    hook = bytes.fromhex("77 02 cb 32 1a 00 0c 1b")
    original_moves = bytes.fromhex("08 00 1a 00 08 01 1b 00 08 02 1c 00")
    if not dex.startswith(hook + original_moves, offset):
        raise ValueError("Unsupported candidate holder bytecode")
    result = bytearray(dex)
    result[offset:offset + len(hook)] = bytes(len(hook))
    result[12:32] = hashlib.sha1(result[32:]).digest()
    struct.pack_into("<I", result, 8, zlib.adler32(result[12:]))
    return result


def bump_manifest(data):
    for old, new in [
        (struct.pack("<HBBI", 8, 0, 0x10, 176071783), struct.pack("<HBBI", 8, 0, 0x10, 176071784)),
        ("18.6.3-asi0.14".encode("utf-16le"), "18.6.3-asi0.15".encode("utf-16le")),
    ]:
        if data.count(old) != 1:
            raise ValueError("Unexpected base manifest")
        data = data.replace(old, new)
    return data


def build(args):
    with args.base.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != BASE_SHA:
            raise ValueError("Expected the original signed Gboard-ASI-0.14.apk")
    if args.out.exists() or not os.environ.get("ASI_KS_PASS"):
        raise ValueError("Choose a new output path and set ASI_KS_PASS for the ASI signing key")
    with tempfile.TemporaryDirectory() as temporary, zipfile.ZipFile(args.base) as base:
        work = Path(temporary)
        manifest = bump_manifest(base.read("AndroidManifest.xml"))
        modified = work / "patched.zip"
        name = "classes2.dex"
        with zipfile.ZipFile(modified, "w") as patch:
            patch.writestr(name, restore_native_candidates(base.read(name)))
        package_update(args, work, base, modified, manifest, {name: name})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("base", "tools", "keystore", "out"):
        parser.add_argument("--" + flag, required=True, type=Path)
    build(parser.parse_args())
