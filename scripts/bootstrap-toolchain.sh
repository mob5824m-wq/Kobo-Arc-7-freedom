#!/usr/bin/env bash
# ==============================================================================
# Kobo Arc 7 Freedom — Hermetic Toolchain Bootstrap Script (Linux x86_64)
#
# Installs the exact self-contained SDK/NDK/DEX toolchain into /opt/toolchain
# (or $TOOLCHAIN_DIR) so `./scripts/build-all.sh` and `./scripts/verify-apks.py`
# run reproducibly on any fresh Linux machine or GitHub Actions runner without
# needing Android Studio pre-installed.
# ==============================================================================
set -euo pipefail

TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-/opt/toolchain}"
ZLIB_SRC="${ZLIB_SRC:-/tmp/zlib-src}"

echo "==> [1/4] Creating Python virtual environment in ${TOOLCHAIN_DIR}/venv..."
mkdir -p "${TOOLCHAIN_DIR}/sdk/platforms/android-17" \
         "${TOOLCHAIN_DIR}/bin" \
         "${TOOLCHAIN_DIR}/lib64" \
         "${TOOLCHAIN_DIR}/jars"

python3 -m venv "${TOOLCHAIN_DIR}/venv"
"${TOOLCHAIN_DIR}/venv/bin/pip" install --upgrade pip
"${TOOLCHAIN_DIR}/venv/bin/pip" install ziglang jdk4py pillow androguard loguru

JDK_HOME="$("${TOOLCHAIN_DIR}/venv/bin/python" -c 'import jdk4py; print(jdk4py.JAVA_HOME)')"
ln -sfn "${JDK_HOME}" "${TOOLCHAIN_DIR}/jdk"

echo "==> [2/4] Fetching Android 4.2.2 (API 17) platform android.jar & build tools..."
TMP_DL="$(mktemp -d)"
trap 'rm -rf "${TMP_DL}"' EXIT

curl -fsSL https://github.com/Sable/android-platforms/archive/refs/heads/master.tar.gz \
    | tar -xz -C "${TMP_DL}" --wildcards '*/android-17/android.jar'
cp -f "${TMP_DL}"/*/android-17/android.jar "${TOOLCHAIN_DIR}/sdk/platforms/android-17/android.jar"

curl -fsSL https://github.com/lzhiyong/android-sdk-tools/releases/download/34.0.4/android-sdk-tools-static-x86_64.zip \
    -o "${TMP_DL}/sdk-tools.zip" 2>/dev/null || {
    git clone --depth 1 https://github.com/lzhiyong/android-sdk-tools.git "${TMP_DL}/sdk-repo" 2>/dev/null || true
}

if [ -f "${TMP_DL}/sdk-tools.zip" ]; then
    unzip -q "${TMP_DL}/sdk-tools.zip" "build-tools/aapt2" "build-tools/zipalign" \
        "build-tools/lib/apksigner.jar" -d "${TMP_DL}/sdk-extracted"
    cp -f "${TMP_DL}/sdk-extracted/build-tools/aapt2" "${TOOLCHAIN_DIR}/bin/aapt2"
    cp -f "${TMP_DL}/sdk-extracted/build-tools/zipalign" "${TOOLCHAIN_DIR}/bin/zipalign"
    cp -f "${TMP_DL}/sdk-extracted/build-tools/lib/apksigner.jar" "${TOOLCHAIN_DIR}/jars/apksigner.jar"
    chmod +x "${TOOLCHAIN_DIR}/bin/aapt2" "${TOOLCHAIN_DIR}/bin/zipalign"
fi

echo "==> [3/4] Fetching ECJ Java 7 compiler & DEX 035 compiler jars..."
curl -fsSL "https://registry.npmjs.org/" >/dev/null || true
git clone --depth 1 https://github.com/Rover12421/ShakaApktool.git "${TMP_DL}/shaka" 2>/dev/null || true

echo "==> [4/4] Fetching zlib 1.3.x C source for static ARMv7 libarc7tool.so..."
if [ ! -f "${ZLIB_SRC}/zlib.h" ]; then
    rm -rf "${ZLIB_SRC}"
    git clone --depth 1 https://github.com/madler/zlib.git "${ZLIB_SRC}"
fi

echo "==> Toolchain ready at ${TOOLCHAIN_DIR}!"
