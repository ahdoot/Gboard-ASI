# Build Gboard ASI

This variant calls the exported Android speech recognition service in the Pixel system package `com.google.android.as`. The keyboard requests recognition on a mic tap and commits the final result into the active text field. The call-mode button is shown inside the keyboard window and uses the same recognizer. Audio capture and call routing are managed by Android and the selected service.

## Install and check

1. Install the signed Gboard ASI APK. Enable and select **Gboard ASI** in Android's on-screen keyboard settings.
2. In app permissions, allow **Microphone** while using the app.
3. Dictate a short phrase in a text field with the toolbar mic. Start a phone call, return to the same field, and tap **Dictate**. Try the same audio route used for your comparison keyboard.
4. Partial results appear on the button. Pause or tap again to finish. The final phrase is inserted into the active field. Changing fields cancels an active session. A recognition error displays the service's error number.

## Build the signed APK

The input is the upstream **Gboard Pro v2.1.1 arm64 release APK**. The build validates its SHA-256 before modifying it. Use Python 3, JDK 17 or later, Apktool 2.12.1, and Android SDK platform 35 and build tools 35.0.0. Have a PKCS12 signing key with the alias `asi` and set `ASI_KS_PASS` in the environment. Retain that same private key for updates.

From the fork root, with `ANDROID_HOME` and `APKTOOL_JAR` set, run

```bash
curl -fL https://github.com/smartworldarafath/Gboard_Pro/releases/download/v2.1.1/Gboard_Pro.apk -o Gboard_Pro.apk
python3 asi/build.py --base Gboard_Pro.apk \
  --android-jar "$ANDROID_HOME/platforms/android-35/android.jar" \
  --build-tools "$ANDROID_HOME/build-tools/35.0.0" \
  --apktool "$APKTOOL_JAR" --keystore asi.p12 --out Gboard-ASI-0.1.apk
```

The fork has its own application ID, `com.gboardpro.asi`. Its manifest explicitly queries the Pixel system package. Signing and 16 KiB APK alignment are verified at the end of the build. Live call behavior needs verification on a physical Pixel.
