#!/usr/bin/env bash
# ==============================================================================
# Kobo Arc 7 Freedom — USB ADB Sideload & Stuck-Setup-Screen Rescue Helper
#
# Installs the complete KoboArc7Freedom.apk offline patch bundle onto a
# Kobo Arc 7 (T647) or Kobo Arc 7HD (T416) over USB ADB, and immediately opens
# Kobo Arc 7 Freedom even if the tablet is stuck on Kobo's dead Wi-Fi Setup
# Wizard screen!
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAIN_APK="${SCRIPT_DIR}/releases/KoboArc7Freedom.apk"

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

echo "==> [1/2] Installing complete KoboArc7Freedom.apk bundle in place (data retained)..."
adb install -r "${MAIN_APK}"

echo "==> [2/2] Opening the shared Install / Repair All Patches pipeline..."
adb shell am start -n ca.arc7freedom.app/.MainActivity --ez show_install_patches true

echo ""
echo "DONE! Look at your Kobo Arc 7 screen now:"
echo "  Rooted: all 34 bundled Home/store/media/default APKs update through the shared pipeline."
echo "  No root: tap Install pending apps and confirm each APK in Android."
echo "  Check the patch report for blocked signatures, storage errors or root-only system steps."
echo "  Rooting, backups, conversion and firmware flashing remain separate explicit actions."
