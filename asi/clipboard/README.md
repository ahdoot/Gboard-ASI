# Gmail clipboard image paste

Gboard ASI 0.13 is an in-place update for the signed 0.12 offline keyboard. If Gmail's focused editor does not advertise a compatible image type, tapping an image tile makes that exact image the current Android clipboard item and asks the active field to paste. The return value acknowledges dispatch of the command. Gmail controls insertion and rendering in the draft.

Build with Python 3.11+, Android platform 35, Android build-tools 35.0.0, and Apktool 2.12.1. Set `ASI_KS_PASS` for the original ASI signing key (alias `asi`). From this directory run

```bash
python3 build.py --base Gboard-ASI-0.12.apk \
  --android-jar "$ANDROID_HOME/platforms/android-35/android.jar" \
  --tools "$ANDROID_HOME/build-tools/35.0.0" --apktool "$APKTOOL_JAR" \
  --keystore asi.p12 --out Gboard-ASI-0.13.apk
```

To check on a phone, open a Gmail reply, place the cursor in the body, and tap a screenshot from the keyboard clipboard. An older image tile should insert the selected screenshot even if a more recent item is text.
