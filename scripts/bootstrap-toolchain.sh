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

if [ ! -x "${TOOLCHAIN_DIR}/venv/bin/python" ]; then
    python3 -m venv "${TOOLCHAIN_DIR}/venv"
    "${TOOLCHAIN_DIR}/venv/bin/pip" install --upgrade pip
fi
"${TOOLCHAIN_DIR}/venv/bin/pip" install -q ziglang jdk4py pillow androguard loguru cryptography certifi pillow

JDK_HOME="$("${TOOLCHAIN_DIR}/venv/bin/python" -c 'import jdk4py; print(jdk4py.JAVA_HOME)')"
ln -sfn "${JDK_HOME}" "${TOOLCHAIN_DIR}/jdk"

echo "==> [2/4] Fetching Android 4.2.2 (API 17) platform android.jar & build tools..."
TMP_DL="$(mktemp -d)"
trap 'rm -rf "${TMP_DL}"' EXIT

if [ ! -s "${TOOLCHAIN_DIR}/sdk/platforms/android-17/android.jar" ]; then
    curl -fsSL https://codeload.github.com/Sable/android-platforms/tar.gz/refs/heads/master \
        | tar -xz -C "${TMP_DL}" --wildcards '*/android-17/android.jar'
    cp -f "${TMP_DL}"/*/android-17/android.jar "${TOOLCHAIN_DIR}/sdk/platforms/android-17/android.jar"
fi

if [ ! -x "${TOOLCHAIN_DIR}/bin/aapt2" ] || [ ! -x "${TOOLCHAIN_DIR}/bin/zipalign" ]; then
    curl -fsSL https://registry.npmjs.org/aaptjs3/-/aaptjs3-2.0.2.tgz \
        | tar -xz -C "${TMP_DL}"
    cp -f "${TMP_DL}/package/bin/x64/linux/aapt2" "${TOOLCHAIN_DIR}/bin/aapt2"
    curl -fsSL https://codeload.github.com/maxim-saplin/ynavi-zee/tar.gz/refs/heads/main \
        | tar -xz -C "${TMP_DL}" ynavi-zee-main/linux
    cp -f "${TMP_DL}/ynavi-zee-main/linux/zipalign.bin" "${TOOLCHAIN_DIR}/bin/zipalign"
    cp -Pf "${TMP_DL}"/ynavi-zee-main/linux/lib64/* "${TOOLCHAIN_DIR}/lib64/"
    chmod +x "${TOOLCHAIN_DIR}/bin/aapt2" "${TOOLCHAIN_DIR}/bin/zipalign"
fi

echo "==> [3/4] Fetching ECJ compiler, DEX 035 compiler (d8.jar), and apksigner.jar..."
if [ ! -s "${TOOLCHAIN_DIR}/jars/d8.jar" ] || [ ! -s "${TOOLCHAIN_DIR}/jars/apksigner.jar" ] || [ ! -s "${TOOLCHAIN_DIR}/jars/ecj-3.45.0.jar" ]; then
    curl -fsSL https://codeload.github.com/Promastergame/tinyapk-lab/tar.gz/refs/heads/main \
        | tar -xz -C "${TMP_DL}" || true
    curl -fsSL https://codeload.github.com/maxim-saplin/ynavi-zee/tar.gz/refs/heads/main \
        | tar -xz -C "${TMP_DL}" || true
    for pair in "d8.jar:d8.jar" "apksigner.jar:apksigner.jar" "ecj*.jar:ecj-3.45.0.jar"; do
        pat="${pair%%:*}"
        dst="${pair##*:}"
        found="$(find "${TMP_DL}" -type f -name "${pat}" | head -n 1 || true)"
        if [ -n "${found}" ]; then
            cp -f "${found}" "${TOOLCHAIN_DIR}/jars/${dst}"
        fi
    done
fi

echo "==> [4/4] Fetching zlib 1.3.x C source for static ARMv7 libarc7tool.so..."
if [ ! -f "${ZLIB_SRC}/zlib.h" ]; then
    rm -rf "${ZLIB_SRC}"
    git clone --depth 1 https://github.com/madler/zlib.git "${ZLIB_SRC}"
fi

echo "==> Toolchain ready at ${TOOLCHAIN_DIR}!"
