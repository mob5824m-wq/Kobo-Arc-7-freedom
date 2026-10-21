#!/usr/bin/env bash
# ==============================================================================
# Kobo Arc 7 Freedom — USB ADB Sideload & Stuck-Setup-Screen Rescue Helper
#
# Installs KoboArc7Freedom.apk (and optionally VanillaAndroid4Home.apk) onto a
# Kobo Arc 7 (T647) or Kobo Arc 7HD (T416) over USB ADB, and immediately opens
# Kobo Arc 7 Freedom even if the tablet is stuck on Kobo's dead Wi-Fi Setup
# Wizard screen!
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAIN_APK="${SCRIPT_DIR}/releases/KoboArc7Freedom.apk"
HOME_APK="${SCRIPT_DIR}/releases/VanillaAndroid4Home.apk"

if ! command -v adb >/dev/null 2>&1; then
    echo "ERROR: 'adb' not found in PATH."
    echo "Install Android Platform Tools (sudo apt install adb / brew install android-platform-tools)"
    echo "or copy releases/KoboArc7Freedom.apk onto a FAT32 microSD card and tap it on the tablet."
    exit 1
fi

if [ ! -f "${MAIN_APK}" ]; then
    echo "ERROR: ${MAIN_APK} not found. Run ./scripts/build-all.sh first."
    exit 1
fi

echo "==> Waiting for Kobo Arc 7 over USB ADB..."
echo "    (Tip: On Kobo Arc 7, ensure USB Debugging is enabled, or if on Kobo Setup"
echo "     Wizard with ADB active, accept the RSA prompt on screen.)"
adb wait-for-device

MODEL="$(adb shell getprop ro.product.model | tr -d '\r')"
DEVICE="$(adb shell getprop ro.product.device | tr -d '\r')"
ANDROID_VER="$(adb shell getprop ro.build.version.release | tr -d '\r')"
echo "==> Connected device: ${MODEL} (${DEVICE}) running Android ${ANDROID_VER}"

echo "==> [1/3] Installing KoboArc7Freedom.apk..."
adb install -r "${MAIN_APK}"

if [ -f "${HOME_APK}" ]; then
    echo "==> [2/3] Installing standalone VanillaAndroid4Home.apk launcher..."
    adb install -r "${HOME_APK}"
fi

echo "==> [3/3] Launching Kobo Arc 7 Freedom on tablet screen (bypasses stuck Kobo Setup Wizard)..."
adb shell am start -n ca.arc7freedom.app/.MainActivity

echo ""
echo "DONE! Look at your Kobo Arc 7 screen now:"
echo "  Step 1 -> Tap 'ROOT TABLET NOW' in Tab 1 (Root)"
echo "  Step 2 -> Back up your factory OS to microSD in Tab 2 (Backup OS)"
echo "  Step 3 -> Convert to Clean Stock Android 4.2 or flash a ROM zip in Tab 3 (Android 4)"
