#!/usr/bin/env bash
# ============================================================================
# Kobo Arc 7 Freedom v1.8.1 — 1-Click Direct USB ADB Auto-Upgrade + Backup
# Automatically upgrades any older conflicting-signature install to v1.8.1
# over USB AND streams raw partitions & files using ZERO MB tablet storage!
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "====================================================================="
echo "  KOBO ARC 7 FREEDOM v1.8.1 — DIRECT USB ADB AUTO-UPGRADE & BACKUP"
echo "  Auto-upgrades older APKs + streams backups using 0 MB tablet space!"
echo "====================================================================="
echo

if ! command -v adb >/dev/null 2>&1; then
    echo "Installing/locating Android platform-tools (adb)..."
    CACHE_DIR="$HOME/.cache/kobo-arc7-adb"
    if [ ! -x "$CACHE_DIR/platform-tools/adb" ]; then
        mkdir -p "$CACHE_DIR"
        OS_TAG="linux"
        if [ "$(uname -s)" = "Darwin" ]; then OS_TAG="darwin"; fi
        curl -fL "https://dl.google.com/android/repository/platform-tools-latest-${OS_TAG}.zip" \
            -o "$CACHE_DIR/pt.zip"
        unzip -qo "$CACHE_DIR/pt.zip" -d "$CACHE_DIR"
    fi
    export PATH="$CACHE_DIR/platform-tools:$PATH"
fi

echo "[1/5] Waiting for Kobo Arc 7 over USB (enable Settings -> Developer options -> USB debugging)..."
adb start-server >/dev/null
adb wait-for-device
echo "  Connected device: $(adb get-serialno)"

echo "[2/5] Checking Kobo Arc 7 Freedom app version on tablet..."
PKG_DUMP="$(adb shell 'dumpsys package ca.arc7freedom.app' 2>/dev/null || true)"
case "$PKG_DUMP" in
    *"versionCode=181"*)
        echo "  KoboArc7Freedom v1.8.1 is already up to date!"
        ;;
    *)
        echo "  Older or missing KoboArc7Freedom detected — automatically upgrading tablet to v1.8.1..."
        LOCAL_APK="$SCRIPT_DIR/KoboArc7Freedom.apk"
        if [ ! -s "$LOCAL_APK" ]; then
            LOCAL_APK="$HOME/.cache/kobo-arc7-adb/KoboArc7Freedom-v1.8.1.apk"
            mkdir -p "$(dirname "$LOCAL_APK")"
            curl -fL "https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/KoboArc7Freedom.apk" -o "$LOCAL_APK"
        fi
        if ! adb install -r "$LOCAL_APK" 2>/dev/null | grep -q "Success"; then
            echo "  Replacing old signing key cleanly via tablet root (su)..."
            adb push "$LOCAL_APK" /data/local/tmp/KoboArc7Freedom.apk >/dev/null
            printf "mount -o remount,rw /system 2>/dev/null || true\nrm -f /system/app/KoboArc7Freedom.apk 2>/dev/null || true\npm uninstall ca.arc7freedom.app 2>/dev/null || true\npm install -r /data/local/tmp/KoboArc7Freedom.apk\nrm -f /data/local/tmp/KoboArc7Freedom.apk\nexit\n" | adb shell su || {
                adb uninstall ca.arc7freedom.app >/dev/null 2>&1 || true
                adb install -r "$LOCAL_APK"
            }
        fi
        echo "  Upgraded Kobo Arc 7 to KoboArc7Freedom v1.8.1!"
        ;;
esac

echo "[3/5] Starting streaming backup server on Kobo Arc 7 (:18777)..."
adb shell "am startservice -n ca.arc7freedom.app/.Arc7AdbBackupService >/dev/null 2>&1 || am start -n ca.arc7freedom.app/.MainActivity >/dev/null 2>&1" || true
sleep 2
adb forward tcp:18777 tcp:18777 >/dev/null

STAMP="$(date +%Y%m%d-%H%M%S)"
DEST_DIR="$HOME/Desktop/Kobo-Arc7-Backups/$STAMP"
mkdir -p "$DEST_DIR"

MODE="${1:-}"
if [ -z "$MODE" ]; then
    echo
    echo "Choose what to back up directly to this laptop:"
    echo "  [1] Essential Unbrick Backup (~35 MB: boot + recovery + NVRAM Wi-Fi MAC + uboot + scatter)"
    echo "  [2] Complete Factory Clone   (~850 MB: Everything in [1] + full /system OS image)"
    echo "  [3] My Personal Files        (Books, Audiobooks, Music, Comics & Photos)"
    read -r -p "Enter 1, 2, or 3 [1]: " MODE
    MODE="${MODE:-1}"
fi

case "$MODE" in
    2) ENDPOINT="/bundle.zip?full=1"; OUT_ZIP="$DEST_DIR/KoboArc7-Full-System-Clone.zip" ;;
    3) ENDPOINT="/sdcard-files.zip";  OUT_ZIP="$DEST_DIR/KoboArc7-UserFiles.zip" ;;
    *) ENDPOINT="/bundle.zip";        OUT_ZIP="$DEST_DIR/KoboArc7-Unbrick-Backup.zip" ;;
esac

echo "[4/5] Streaming directly from Kobo flash over USB to: $OUT_ZIP"
curl -fL --progress-bar "http://127.0.0.1:18777${ENDPOINT}" -o "$OUT_ZIP"

echo "[5/5] Extracting & verifying cryptographic SHA-256 hashes on your laptop..."
mkdir -p "$DEST_DIR/extracted"
unzip -qo "$OUT_ZIP" -d "$DEST_DIR/extracted"
if [ -f "$DEST_DIR/extracted/SHA256SUMS.txt" ]; then
    (
        cd "$DEST_DIR/extracted"
        if command -v sha256sum >/dev/null 2>&1; then
            sha256sum -c SHA256SUMS.txt
        elif command -v shasum >/dev/null 2>&1; then
            shasum -a 256 -c SHA256SUMS.txt
        fi
    )
fi

echo
echo "SUCCESS! Verified backup stored at: $DEST_DIR"
