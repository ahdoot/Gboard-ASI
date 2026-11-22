## Overview

Gboard Pro **v2.1.1** updates the core base package to the latest official **Gboard 18.6.3** (`18.6.3.1002251336-release-arm64-v8a`, `versionCode: 176071768`), achieving complete upstream parity with PixelBoard 1.3.6 while preserving the full Gboard Pro identity (`com.gboardpro.app`), Material 3 Expressive UI, and custom features.

---

## What's Changed

### Core Base Upgrade
* **Rebased onto Gboard 18.6.3**: Updated from `18.4.1.985164140` to official Google Gboard `18.6.3.1002251336-release-arm64-v8a` (`versionCode: 176071768`), providing the latest stock layout improvements, engine optimizations, and upstream stability enhancements.
* **Full PixelBoard 1.3.6 Feature Parity**:
  * **Candidate Strip Emoji Slot Placement**: Positions suggested emoji candidates on the right side of the candidate strip (Slot 2 in standard 3-slot strip), leaving left and center slots open for raw verbatim speech and predictive completions.
  * **Safe Keypress & Cursor Drag Haptic Feedback**: Explicitly passes `FLAG_IGNORE_GLOBAL_SETTING` on keypress and cursor drag haptics, guaranteeing tactile feedback on Honor MagicOS, Huawei EMUI, and devices with system-wide vibration disabled.
  * **Screen Rotation Grace Period for Rambler Dictation**: 2500ms grace window with 1000ms baseline delay and active display rotation listener prevents voice session drops during screen rotations and foldable device unfolds.
  * **Speech Diff Preservation & Microsoft Word / Office Sync**: Retains speech diffs for voice replacements and mid-sentence voice edits while automatically adapting for Microsoft Word and Office apps to prevent cursor desynchronization.
  * **Header Minimize Key Positioning**: Places the Rambler minimize key next to the close button on the left header position (`key_pos_header_start_extra_key`).
  * **Rambler Stock Policy & Jetson Cursor Awareness**: Enforces `jetson_enable_cursor_awareness` in stock phenotype policy for voice editing accuracy.
  * **AI Writing Tools Connectivity & 2000-Word Limit**: Enforces a 2000-word limit for Proofread and Rewrite, updates candidate threshold to 0.0, enables proofread apps allowlist, and forces v2 landscape layout.
  * **Android 10+ (API 29) Minimum SDK Compatibility**: `minSdkVersion` set to 29, supporting Android 10, 11, 12, 13, 14, 15, and 16.
  * **Show in App List Toggle**: Unlocked launcher icon visibility toggle across all devices.

### Gboard Pro Exclusive Features Preserved
* **Standalone Coexistence**: Installed as `com.gboardpro.app` with app name `Gboard Pro`, allowing side-by-side operation with stock Gboard without root.
* **Custom Appearance Themes**: Material 3 Expressive theme palettes including Blush Lilac, Cotton Candy, Sunset Peach, Fresh Mint, and AMOLED Pure Black dark mode.
* **Extended 24-Hour Clipboard Retention**: Unpinned clips retained for 24 hours with 100 recent clip cap and discrete slider options (1h, 2h, 3h, 6h, 12h, 18h, 24h).
* **Material 3 Expressive About Screen**: Native M3 About interface featuring real-time Mbps download speed meter and 8-hour background auto-updater targeting `smartworldarafath/Gboard_Pro`.
* **High Refresh Rate & Smooth Transitions**: 144Hz / 120Hz IME window refresh rate enforcement and fluid Material 3 activity transitions.

---

## Installation & Upgrade

1. Download `Gboard_Pro.apk` from the Assets section below.
2. Install the APK directly on your device (Android 10+ supported; no root required).
3. If upgrading from Gboard Pro v2.1.0 or earlier, install directly over your existing installation to preserve all settings, clipboard history, and dictionary data.
4. Enable **Gboard Pro** in **Settings > System > Languages & input > On-screen keyboard**.

---

## Package Details
* **App Name**: Gboard Pro
* **Package Name**: `com.gboardpro.app`
* **Version Name**: `18.6.3.1002251336-release-arm64-v8a`
* **Version Code**: `176071768`
* **Patch Version**: `v2.1.1`
* **Min SDK**: 29 (Android 10+)
* **Target SDK**: 37 (Android 16+)
* **Architecture**: `arm64-v8a`
* **Signature**: APK Signature Scheme v3 (Verified)

---

## Checksums
* `Gboard_Pro.apk`: `7d1ef89151b1c239c52e252455201823a5d65a163ff58688381b3019f83f6a76`
