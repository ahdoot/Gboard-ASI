#!/usr/bin/env python3
"""Build the ASI variant from the exact upstream Gboard Pro v2.1.1 arm64 release."""
import argparse
import copy
import hashlib
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile
import zlib

BASE_SHA256 = 'c7d6fdd541674a91abf28c71f9f880ed956db3767ad3ee4911a343a4a2a54f86'
BRIDGE = 'Llocal/gboard/asi/AsiVoice;'
IME = 'Landroid/inputmethodservice/InputMethodService;'


def run(*args):
    subprocess.run([str(a) for a in args], check=True)


def change_method(smali, signature, transform):
    pattern = re.compile(r'^\.method public final ' + re.escape(signature) + r'\n.*?^\.end method$', re.M | re.S)
    result, count = pattern.subn(lambda m: transform(m.group()), smali)
    if count != 1:
        raise ValueError(f'Expected one IME method {signature}, found {count}')
    return result


def prologue(method, instructions):
    return re.sub(r'(^    \.locals \d+\n)', lambda m: m.group() + instructions + '\n', method, count=1, flags=re.M)


def hook_ime(smali):
    # Read Gboard's automatic punctuation switch exactly as its own voice path does in v2.1.1.
    smali = smali.replace('# interfaces\n', '# interfaces\n.implements Llocal/gboard/asi/AsiVoice$VoicePreferences;\n', 1)
    smali += '''
.method public final asiAutoPunctuationEnabled()Z
    .locals 3
    invoke-static {p0}, Lqou;->I(Landroid/content/Context;)Lqou;
    move-result-object v0
    const v1, 0x7f140942
    const/4 v2, 0x1
    invoke-virtual {v0, v1, v2}, Lcdx;->x(IZ)Z
    move-result v0
    return v0
.end method
'''
    # The disabled mic, direct dictation, regular mic and Jetson mic converge in aw().
    # Reuse scratch registers before the original method initializes them.
    mic_dispatch = f"""
    if-eqz p1, :asi_original
    invoke-virtual/range {{p1 .. p1}}, Loas;->a()I
    move-result v0
    const/16 v1, -0x273a
    if-eq v0, v1, :asi_mic
    const/16 v1, -0x277c
    if-eq v0, v1, :asi_mic
    const/16 v1, -0x27ce
    if-eq v0, v1, :asi_mic
    const/16 v1, -0x27e9
    if-ne v0, v1, :asi_original
    :asi_mic
    invoke-virtual/range {{p1 .. p1}}, Loas;->g()Lpue;
    move-result-object v2
    iget-object v2, v2, Lpue;->e:Ljava/lang/Object;
    move-object/from16 v3, p0
    invoke-static {{v3, v0, v2}}, {BRIDGE}->mic({IME}ILjava/lang/Object;)Z
    move-result v0
    if-eqz v0, :asi_original
    return v0
    :asi_original"""
    # p1 is above v15 here. Dalvik's if-eqz accepts any 8-bit register.
    smali = change_method(smali, 'aw(Loas;)Z', lambda m: prologue(m, mic_dispatch))
    for name in ('onStartInputView(Landroid/view/inputmethod/EditorInfo;Z)V', 'onWindowShown()V',
                 'onStartInput(Landroid/view/inputmethod/EditorInfo;Z)V', 'onFinishInputView(Z)V',
                 'onWindowHidden()V', 'onFinishInput()V', 'onDestroy()V'):
        op = 'show' if name.startswith(('onStartInputView', 'onWindowShown')) else 'hide'
        insn = f'    invoke-static/range {{p0 .. p0}}, {BRIDGE}->{op}({IME})V'
        smali = change_method(smali, name, lambda m: prologue(m, insn))
    # Gboard's own composer does not own the ASI composing span. Let the framework
    # see our preview echoes without asking the separate Gboard composer to reset it.
    select = f'''    invoke-static/range {{p0 .. p6}}, {BRIDGE}->selection({IME}IIIIII)Z
    move-result v0
    if-eqz v0, :asi_stock_selection
    invoke-super/range {{p0 .. p6}}, {IME}->onUpdateSelection(IIIIII)V
    return-void
    :asi_stock_selection'''
    smali = change_method(smali, 'onUpdateSelection(IIIIII)V', lambda m: prologue(m, select))
    insets = f'    invoke-static/range {{p0 .. p1}}, {BRIDGE}->place({IME}Landroid/inputmethodservice/InputMethodService$Insets;)V'
    return change_method(smali, 'onComputeInsets(Landroid/inputmethodservice/InputMethodService$Insets;)V',
                         lambda m: m.replace('    return-void', insets + '\n    return-void'))


def hook_header(smali):
    # All inflated and dynamically replaced headers have this common holder.
    if (' onAttachedToWindow()V' in smali or ' onConfigurationChanged(' in smali
            or '->onMeasure(II)V' not in smali):
        raise ValueError('Unexpected keyboard holder')
    return smali + f'''
.method protected onAttachedToWindow()V
    .locals 0
    invoke-super {{p0}}, Ltdb;->onAttachedToWindow()V
    invoke-static {{p0}}, {BRIDGE}->reserveToolbarSpace(Landroid/view/View;)V
    return-void
.end method

.method protected onConfigurationChanged(Landroid/content/res/Configuration;)V
    .locals 0
    invoke-super {{p0, p1}}, Ltdb;->onConfigurationChanged(Landroid/content/res/Configuration;)V
    invoke-static {{p0}}, {BRIDGE}->reserveToolbarSpace(Landroid/view/View;)V
    return-void
.end method
'''


def hook_mic_keys(smali):
    indicator = 'Llocal/gboard/asi/MicIndicator;'
    clear = f'    invoke-static {{p1, p0}}, {indicator}->unbind(Landroid/view/View;Ljava/lang/Object;)V'
    smali = change_method(smali, 'k(Landroid/view/View;Z)V', lambda m: prologue(m, clear))
    old_view = f'''
    iget-object v0, p0, Lmng;->a:Landroid/view/View;
    if-eq v0, p2, :asi_same_key
    invoke-static {{v0, p0}}, {indicator}->unbind(Landroid/view/View;Ljava/lang/Object;)V
    :asi_same_key'''
    # The model is authoritative even for toolbar keys whose SoftKeyDef wraps the tap.
    bind = f'''    sget-object v0, Lpti;->a:Lpti;
    invoke-virtual {{p0, v0}}, Lmng;->e(Lpti;)Lcom/google/android/libraries/inputmethod/metadata/ActionDef;
    move-result-object v0
    const/4 v1, 0x0
    if-eqz v0, :asi_bind
    iget-object v0, v0, Lcom/google/android/libraries/inputmethod/metadata/ActionDef;->d:[Lpue;
    if-eqz v0, :asi_bind
    array-length p3, v0
    if-eqz p3, :asi_bind
    aget-object v0, v0, v1
    if-eqz v0, :asi_bind
    iget v1, v0, Lpue;->c:I
    :asi_bind
    iget-object v0, p0, Lmng;->b:Ljava/lang/String;
    invoke-static {{p2, p0, v0, v1}}, {indicator}->bind(Landroid/view/View;Ljava/lang/Object;Ljava/lang/String;I)V
    return-void'''
    return change_method(smali, 'l(Lmnj;Landroid/view/View;Z)V',
                         lambda m: prologue(m, old_view).replace('    return-void', bind))


def bump_version(data):
    # The root AndroidManifest.xml versionCode is one typed integer in the pinned binary XML.
    old = struct.pack('<HBBI', 8, 0, 0x10, 176071770)
    if data.count(old) != 1:
        raise ValueError('Expected exactly one upstream versionCode')
    return data.replace(old, struct.pack('<HBBI', 8, 0, 0x10, 176071775))


def rename(data, old, new):
    if len(old) != len(new):
        raise ValueError('Binary string pool entries must keep their encoded length')
    for encoding in ('utf-8', 'utf-16le'):
        data = data.replace(old.encode(encoding), new.encode(encoding))
    return data


def repackage(source, output, patched_dex, extension):
    with zipfile.ZipFile(source) as base, zipfile.ZipFile(output, 'w') as result:
        for info in base.infolist():
            name = info.filename
            if re.fullmatch(r'META-INF/([^/]+\.(SF|RSA|DSA|EC)|MANIFEST\.MF)', name, re.I):
                continue
            data = patched_dex if name == 'classes2.dex' else base.read(info)
            # All known package references in the pinned release. Framework and app class names are intact.
            if name in ('AndroidManifest.xml', 'resources.arsc', 'res/xml/settings.xml',
                        'res/xml/settings_legacy.xml', 'classes.dex'):
                data = rename(data, 'com.gboardpro.app', 'com.gboardpro.asi')
                if name == 'AndroidManifest.xml':
                    data = rename(data, 'Gboard Pro', 'Gboard ASI')
                    data = bump_version(rename(data, '18.6.3-v2.1.1', '18.6.3-asi0.6'))
                if name == 'classes.dex':
                    body = hashlib.sha1(data[32:]).digest() + data[32:]
                    data = data[:8] + struct.pack('<I', zlib.adler32(body)) + body
            entry = copy.copy(info)
            entry.extra = b''  # zipalign will regenerate page alignment at its new offset.
            result.writestr(entry, data)
        result.write(extension, 'classes6.dex', compress_type=zipfile.ZIP_DEFLATED)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for arg in ('base', 'android-jar', 'build-tools', 'apktool', 'keystore', 'out'):
        p.add_argument('--' + arg, type=Path, required=True)
    p.add_argument('--alias', default='asi')
    p.add_argument('--ecj', type=Path, help='Optional Eclipse compiler jar when javac is unavailable')
    args = p.parse_args()
    if hashlib.sha256(args.base.read_bytes()).hexdigest() != BASE_SHA256:
        p.error('Expected the upstream v2.1.1 release APK (SHA-256 mismatch)')
    if not os.getenv('ASI_KS_PASS'):
        p.error('Set ASI_KS_PASS to the password of your signing key')
    if args.out.exists():
        p.error('Choose a new output filename')
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        t = Path(tmp)
        sources = sorted(Path(__file__).parent.glob('*.java'))
        compiler = (['java', '-jar', args.ecj] if args.ecj else ['javac'])
        (t / 'classes').mkdir()
        run(*compiler, '-source', '8', '-target', '8', '-proc:none', '-classpath', args.android_jar,
            '-d', t / 'classes', *sources)
        with zipfile.ZipFile(t / 'classes.jar', 'w') as z:
            for file in (t / 'classes').rglob('*.class'):
                z.write(file, file.relative_to(t / 'classes'))
        run(args.build_tools / 'd8', '--min-api', '29', '--lib', args.android_jar,
            '--output', t, t / 'classes.jar')
        # Rebuild only the IME's DEX. Keep all other upstream bytecode and compiled resources.
        with zipfile.ZipFile(args.base) as base, zipfile.ZipFile(t / 'input.zip', 'w') as mini:
            mini.writestr('classes.dex', base.read('classes2.dex'))
        apktool = ['java', '-Xmx1200m', '-jar', args.apktool]
        run(*apktool, 'd', '-r', '-j', '2', '-o', t / 'decoded', t / 'input.zip')
        ime_file = t / 'decoded/smali/pbb.smali'
        ime_file.write_text(hook_ime(ime_file.read_text()))
        holder = t / 'decoded/smali/com/google/android/libraries/inputmethod/keyboard/impl/KeyboardViewHolder.smali'
        holder.write_text(hook_header(holder.read_text()))
        keys = t / 'decoded/smali/mng.smali'
        keys.write_text(hook_mic_keys(keys.read_text()))
        run(*apktool, 'b', '-j', '2', '-o', t / 'edited.zip', t / 'decoded')
        with zipfile.ZipFile(t / 'edited.zip') as edited:
            repackage(args.base, t / 'unsigned.apk', edited.read('classes.dex'), t / 'classes.dex')
        run(args.build_tools / 'zipalign', '-P', '16', '-f', '4', t / 'unsigned.apk', t / 'aligned.apk')
        run(args.build_tools / 'apksigner', 'sign', '--ks', args.keystore, '--ks-key-alias', args.alias,
            '--ks-pass', 'env:ASI_KS_PASS', '--v4-signing-enabled', 'false', '--out', t / 'signed.apk', t / 'aligned.apk')
        run(args.build_tools / 'apksigner', 'verify', '--verbose', t / 'signed.apk')
        run(args.build_tools / 'zipalign', '-c', '-P', '16', '4', t / 'signed.apk')
        import shutil
        shutil.copyfile(t / 'signed.apk', args.out)


if __name__ == '__main__':
    main()
