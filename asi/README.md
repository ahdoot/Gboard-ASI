# Build Gboard ASI

This variant calls the exported Android speech recognition service in the Pixel system package `com.google.android.as`. The keyboard displays interim words as composing text in the active editor and applies the final wording once. The call-mode button is shown inside the keyboard window and uses the same recognizer. Audio capture and call routing are managed by Android and the selected service.

## Install and check

1. Install the signed Gboard ASI APK over your existing Gboard ASI. For a fresh installation, enable and select **Gboard ASI** in Android's on-screen keyboard settings.
2. In app permissions, allow **Microphone** while using the app.
3. Tap the toolbar mic once to dictate one phrase. Editors with composing-text support show ASI's interim words at the cursor as they arrive. ASI may revise those words. The toolbar mic turns blue, red, yellow and green while ASI listens, and returns to its themed icon when listening ends.
4. Double-tap the toolbar mic to lock dictation. The mic stays colorful through pauses. After a spoken phrase, the keyboard immediately requests the next phrase on the same ASI connection. Repeated empty responses still back off. Tap the mic or **Mic locked · Stop** to finish. Moving the cursor or leaving the field ends dictation.
5. Try the same gestures during a call using **Dictate**. The right side of the toolbar leaves 76 dp for the floating button on compact screens (smallest width below 600 dp) and 52 dp on larger screens. Position the floating button using its own drag control.

## Build the signed APK

The input is the upstream **Gboard Pro v2.1.1 arm64 release APK**. The build validates its SHA-256 before modifying it. Use Python 3, JDK 17 or later, Apktool 2.12.1, and Android SDK platform 35 and build tools 35.0.0. Have a PKCS12 signing key with the alias `asi` and set `ASI_KS_PASS` in the environment. Retain that same private key for updates.

From the fork root, with `ANDROID_HOME` and `APKTOOL_JAR` set, run

```bash
curl -fL https://github.com/smartworldarafath/Gboard_Pro/releases/download/v2.1.1/Gboard_Pro.apk -o Gboard_Pro.apk
python3 asi/build.py --base Gboard_Pro.apk \
  --android-jar "$ANDROID_HOME/platforms/android-35/android.jar" \
  --build-tools "$ANDROID_HOME/build-tools/35.0.0" \
  --apktool "$APKTOOL_JAR" --keystore asi.p12 --out Gboard-ASI-0.5.apk
```

The fork has its own application ID, `com.gboardpro.asi`. Its manifest explicitly queries the Pixel system package. Signing and 16 KiB APK alignment are verified at the end of the build. Live call behavior needs verification on a physical Pixel.
