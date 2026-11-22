#!/usr/bin/env bash
# ==============================================================================
# Kobo Arc 7 Freedom v2.6.9 — Reproducible Android 4.2.2 (API 17 / DEX 035) release build
#
# `scripts/build-all.sh` builds and signs the complete release set:
#   1. Generates the offline CA bundle, app icons, split-app manifests, and patched F-Droid APK.
#   2. Cross-compiles the static ARMv7 root, TLS, VPN, Python, apt, and partition utilities.
#   3. Builds Home, Aurora Store, VLC fallback, YouTube, YouTube Music, 32 default-app APKs,
#      Arc7 Freedom, and the 1-tap upgrader with the permanent v1/v2/v3 release certificate.
#   4. Generates the local Wi-Fi release portal and `releases/SHA256SUMS.txt`.
#
# Android modules use the conventional `src/main/AndroidManifest.xml`, `src/main/java/`, and
# `src/main/res/` layout. Keep the file paths here in sync with that layout.
# ==============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# bootstrap-toolchain.sh accepts the same override. Prefer /opt on CI, otherwise
# reuse a repository-local install (no writable /opt or global PATH changes required).
if [ -z "${TOOLCHAIN_DIR:-}" ]; then
    if [ -x /opt/toolchain/jdk/bin/java ]; then
        TOOLCHAIN_DIR=/opt/toolchain
    else
        TOOLCHAIN_DIR="$ROOT_DIR/build/toolchain"
    fi
fi
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$TOOLCHAIN_DIR/sdk}}"
LATEST_BUILD_TOOLS=""
if [ -n "$SDK_ROOT" ] && [ -d "$SDK_ROOT/build-tools" ]; then
    LATEST_BUILD_TOOLS="$(ls -1d "$SDK_ROOT"/build-tools/* 2>/dev/null | sort -V | tail -n 1 || true)"
fi

if [ -d "$TOOLCHAIN_DIR/lib64" ]; then
    export LD_LIBRARY_PATH="$TOOLCHAIN_DIR/lib64:${LD_LIBRARY_PATH:-}"
fi

# Resolve JDK tools
if [ -x "$TOOLCHAIN_DIR/jdk/bin/java" ]; then
    JAVA_BIN="${JAVA:-$TOOLCHAIN_DIR/jdk/bin/java}"
    JAVAC_BIN="${JAVAC:-$TOOLCHAIN_DIR/jdk/bin/javac}"
    KEYTOOL_BIN="${KEYTOOL:-$TOOLCHAIN_DIR/jdk/bin/keytool}"
else
    JAVA_BIN="${JAVA:-java}"
    JAVAC_BIN="${JAVAC:-javac}"
    KEYTOOL_BIN="${KEYTOOL:-keytool}"
fi

# Resolve Android SDK tools (aapt2, zipalign, android.jar, d8/dfoda, apksigner)
if [ -x "$TOOLCHAIN_DIR/bin/aapt2" ]; then
    AAPT2="${AAPT2:-$TOOLCHAIN_DIR/bin/aapt2}"
    ZIPALIGN="${ZIPALIGN:-$TOOLCHAIN_DIR/bin/zipalign}"
elif [ -n "$LATEST_BUILD_TOOLS" ]; then
    AAPT2="${AAPT2:-$LATEST_BUILD_TOOLS/aapt2}"
    ZIPALIGN="${ZIPALIGN:-$LATEST_BUILD_TOOLS/zipalign}"
else
    AAPT2="${AAPT2:-aapt2}"
    ZIPALIGN="${ZIPALIGN:-zipalign}"
fi

if [ -f "$TOOLCHAIN_DIR/sdk/platforms/android-17/android.jar" ]; then
    ANDROID_JAR="${ANDROID_JAR:-$TOOLCHAIN_DIR/sdk/platforms/android-17/android.jar}"
elif [ -n "$SDK_ROOT" ] && [ -f "$SDK_ROOT/platforms/android-17/android.jar" ]; then
    ANDROID_JAR="${ANDROID_JAR:-$SDK_ROOT/platforms/android-17/android.jar}"
else
    echo "ERROR: android-17/android.jar not found. Run ./scripts/bootstrap-toolchain.sh or install platforms;android-17."
    exit 1
fi

D8_BIN=""
D8_JAR=""
if [ -x "$TOOLCHAIN_DIR/sdk/build-tools/34.0.0/d8" ]; then
    D8_BIN="$TOOLCHAIN_DIR/sdk/build-tools/34.0.0/d8"
elif [ -n "$LATEST_BUILD_TOOLS" ] && [ -x "$LATEST_BUILD_TOOLS/d8" ]; then
    D8_BIN="$LATEST_BUILD_TOOLS/d8"
elif [ -f "$TOOLCHAIN_DIR/jars/d8.jar" ]; then
    D8_JAR="$TOOLCHAIN_DIR/jars/d8.jar"
fi

if [ -f "$TOOLCHAIN_DIR/jars/ecj-3.45.0.jar" ]; then
    ECJ="${ECJ:-$TOOLCHAIN_DIR/jars/ecj-3.45.0.jar}"
else
    ECJ="${ECJ:-$TOOLCHAIN_DIR/jars/ecj-4.8.jar}"
fi
DFODA="${DFODA:-$TOOLCHAIN_DIR/jars/dfoda0.0.9.jar}"

if [ -f "$TOOLCHAIN_DIR/jars/apksigner.jar" ]; then
    APKSIGNER_JAR="${APKSIGNER:-$TOOLCHAIN_DIR/jars/apksigner.jar}"
elif [ -f "$TOOLCHAIN_DIR/sdk/build-tools/34.0.0/lib/apksigner.jar" ]; then
    APKSIGNER_JAR="${APKSIGNER:-$TOOLCHAIN_DIR/sdk/build-tools/34.0.0/lib/apksigner.jar}"
elif [ -n "$LATEST_BUILD_TOOLS" ] && [ -f "$LATEST_BUILD_TOOLS/lib/apksigner.jar" ]; then
    APKSIGNER_JAR="${APKSIGNER:-$LATEST_BUILD_TOOLS/lib/apksigner.jar}"
else
    echo "ERROR: apksigner.jar not found."
    exit 1
fi

# The F-Droid subprocess compiles the same common Java transport with these tools.
export ECJ D8_JAR ANDROID_JAR

# Resolve Python & Zig C cross-compiler
if [ -x "$TOOLCHAIN_DIR/venv/bin/python" ]; then
    PY_BIN="$TOOLCHAIN_DIR/venv/bin/python"
else
    PY_BIN="${PYTHON:-python3}"
fi

if "$PY_BIN" -c "import ziglang" >/dev/null 2>&1; then
    ZIG_CMD="$PY_BIN -m ziglang"
elif command -v zig >/dev/null 2>&1; then
    ZIG_CMD="zig"
else
    echo "ERROR: Zig C cross-compiler not found ('pip install ziglang' or install zig)."
    exit 1
fi

ZLIB_DIR="${ZLIB_DIR:-/tmp/zlib-src}"
MBEDTLS_DIR="${MBEDTLS_DIR:-/tmp/mbedtls-src}"
BEARSSL_DIR="${BEARSSL_DIR:-/tmp/bearssl-src}"

mkdir -p releases
KEYSTORE_PATH="keystore/arc7freedom-release.jks"
KEYSTORE_PASS="arc7freedom2026"
KEYSTORE_ALIAS="arc7freedom"
if [ ! -f "$KEYSTORE_PATH" ]; then
    echo "ERROR: Permanent release keystore missing at $KEYSTORE_PATH!"
    exit 1
fi

compile_java_and_dex() {
    local out_classes="$1"
    local out_dex_dir="$2"
    shift 2
    # Shared, API-15-compatible glass material used by both launcher and applications.
    set -- "$@" shared/java/ca/arc7freedom/glass/*.java shared/java/ca/arc7freedom/support/*.java

    if [ -f "$ECJ" ]; then
        "$JAVA_BIN" -jar "$ECJ" -1.8 -source 1.8 -target 1.8 \
            -bootclasspath "$ANDROID_JAR" \
            -d "$out_classes" \
            "$@"
    else
        "$JAVAC_BIN" -source 1.8 -target 1.8 \
            -bootclasspath "$ANDROID_JAR" \
            -Xlint:-options -Xlint:-deprecation \
            -d "$out_classes" \
            "$@"
    fi

    (cd "$out_classes" && zip -qr ../classes.jar .)

    if [ -n "$D8_BIN" ]; then
        "$D8_BIN" --min-api 15 --lib "$ANDROID_JAR" \
            --output "$out_dex_dir" \
            "$out_classes/../classes.jar"
    elif [ -n "$D8_JAR" ]; then
        "$JAVA_BIN" -cp "$D8_JAR" com.android.tools.r8.D8 \
            --min-api 15 --lib "$ANDROID_JAR" \
            --output "$out_dex_dir" \
            "$out_classes/../classes.jar"
    else
        "$JAVA_BIN" -cp "build:$DFODA" RunDexer \
            --dex "--saida=$out_dex_dir/classes.dex" \
            "$out_classes/../classes.jar"
    fi
}

# Fail fast if workflow names, Android versions or generated manifests drift apart.
"$PY_BIN" scripts/check-release-version.py

echo "==> [1/7] Pre-packaging offline 2026 CAs & icons..."
# Regenerate all manifests before building YouTube/Music as well as the default apps.
"$PY_BIN" scripts/gen-split-apps.py
"$PY_BIN" scripts/generate-cacerts.py
"$PY_BIN" scripts/generate-icons.py


echo "==> [2/7] Cross-compiling static ARMv7 EABI5 native binaries (su, arc7root, arc7tool, arc7tls w/ BearSSL+MbedTLS)..."
if [ ! -d "$ZLIB_DIR" ]; then
    git clone --depth 1 https://github.com/madler/zlib.git "$ZLIB_DIR"
fi
if [ ! -d "$MBEDTLS_DIR" ]; then
    git clone --depth 1 --branch v3.6.2 https://github.com/Mbed-TLS/mbedtls.git "$MBEDTLS_DIR"
fi
if [ ! -d "$BEARSSL_DIR" ]; then
    git clone --depth 1 https://github.com/unkaktus/bearssl.git "$BEARSSL_DIR"
fi

mkdir -p build/native/armeabi-v7a build/native/armeabi build/mbedtls-arm build/bearssl-arm
CFLAGS_ARM="-target arm-linux-musleabi -march=armv7-a -mcpu=cortex_a8 -Os -s -static -Wall -Wextra -Wno-unused-parameter"

if [ ! -f build/mbedtls-arm/libmbedtls_all.a ] || [ ! -f build/bearssl-arm/libbearssl_all.a ]; then
    "$PY_BIN" - "$MBEDTLS_DIR" "$BEARSSL_DIR" << 'PYEOF'
import concurrent.futures, glob, os, subprocess, sys
mbed, bear = sys.argv[1], sys.argv[2]
zig = [sys.executable, "-m", "ziglang"]
try:
    import ziglang  # noqa
except ImportError:
    zig = ["zig"]

if not os.path.exists("build/mbedtls-arm/libmbedtls_all.a"):
    srcs = sorted(glob.glob(os.path.join(mbed, "library/*.c")))
    def comp_m(s):
        o = os.path.join("build/mbedtls-arm", os.path.basename(s)[:-2] + ".o")
        subprocess.run(
            zig + ["cc", "-target", "arm-linux-musleabi", "-march=armv7-a", "-mcpu=cortex_a8",
                   "-Os", "-ffunction-sections", "-fdata-sections",
                   "-DMBEDTLS_THREADING_C", "-DMBEDTLS_THREADING_PTHREAD",
                   f"-I{mbed}/include", f"-I{mbed}/library", "-c", s, "-o", o],
            check=True,
        )
        return o
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        objs = list(ex.map(comp_m, srcs))
    subprocess.run(zig + ["ar", "rcs", "build/mbedtls-arm/libmbedtls_all.a", *objs], check=True)

if not os.path.exists("build/bearssl-arm/libbearssl_all.a"):
    bsrcs = sorted(
        s for s in glob.glob(os.path.join(bear, "src/**/*.c"), recursive=True)
        if not os.path.basename(s).startswith("ec_prime_i31_secp")
    )
    def comp_b(idx_s):
        idx, s = idx_s
        o = os.path.join("build/bearssl-arm", f"{idx:03d}_{os.path.basename(s)[:-2]}.o")
        subprocess.run(
            zig + ["cc", "-target", "arm-linux-musleabi", "-march=armv7-a", "-mcpu=cortex_a8",
                   "-Os", "-ffunction-sections", "-fdata-sections",
                   f"-I{bear}/inc", f"-I{bear}/src", "-c", s, "-o", o],
            check=True,
        )
        return o
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        bobjs = list(ex.map(comp_b, enumerate(bsrcs)))
    subprocess.run(zig + ["ar", "rcs", "build/bearssl-arm/libbearssl_all.a", *bobjs], check=True)
PYEOF
fi

$ZIG_CMD cc $CFLAGS_ARM native/su.c -o build/native/armeabi-v7a/libarc7su.so
$ZIG_CMD cc $CFLAGS_ARM native/arc7root.so.tmp 2>/dev/null || true
rm -f build/native/armeabi-v7a/libarc7root.so.tmp
$ZIG_CMD cc $CFLAGS_ARM native/arc7root.c -o build/native/armeabi-v7a/libarc7root.so -lpthread
$ZIG_CMD cc $CFLAGS_ARM -DHAVE_UNISTD_H=1 -DHAVE_STDARG_H=1 -I"$ZLIB_DIR" native/arc7tool.c \
    "$ZLIB_DIR"/adler32.c "$ZLIB_DIR"/compress.c "$ZLIB_DIR"/crc32.c \
    "$ZLIB_DIR"/deflate.c "$ZLIB_DIR"/gzclose.c "$ZLIB_DIR"/gzlib.c \
    "$ZLIB_DIR"/gzread.c "$ZLIB_DIR"/gzwrite.c "$ZLIB_DIR"/infback.c \
    "$ZLIB_DIR"/inffast.c "$ZLIB_DIR"/inflate.c "$ZLIB_DIR"/inftrees.c \
    "$ZLIB_DIR"/trees.c "$ZLIB_DIR"/uncompr.c "$ZLIB_DIR"/zutil.c \
    -o build/native/armeabi-v7a/libarc7tool.so
$ZIG_CMD cc $CFLAGS_ARM -ffunction-sections -fdata-sections -Wl,--gc-sections \
    -DMBEDTLS_THREADING_C -DMBEDTLS_THREADING_PTHREAD \
    -I"$MBEDTLS_DIR/include" -I"$BEARSSL_DIR/inc" \
    native/arc7tls.c \
    build/mbedtls-arm/libmbedtls_all.a \
    build/bearssl-arm/libbearssl_all.a \
    -lpthread \
    -o build/native/armeabi-v7a/libarc7tls.so

$ZIG_CMD cc $CFLAGS_ARM -ffunction-sections -fdata-sections -Wl,--gc-sections \
    -DMBEDTLS_THREADING_C -DMBEDTLS_THREADING_PTHREAD \
    -I"$MBEDTLS_DIR/include" -I"$BEARSSL_DIR/inc" \
    native/arc7vpn.c \
    build/mbedtls-arm/libmbedtls_all.a \
    build/bearssl-arm/libbearssl_all.a \
    -lpthread \
    -o build/native/armeabi-v7a/libarc7vpn.so

mkdir -p build/native/host build/bearssl-host build/mbedtls-host
if [ ! -f build/mbedtls-host/libmbedtls_host.a ] || [ ! -f build/bearssl-host/libbearssl_host.a ]; then
    "$PY_BIN" - "$MBEDTLS_DIR" "$BEARSSL_DIR" << 'PYEOF'
import concurrent.futures, glob, os, subprocess, sys
mbed, bear = sys.argv[1], sys.argv[2]
zig = [sys.executable, "-m", "ziglang"]
try:
    import ziglang  # noqa
except ImportError:
    zig = ["zig"]
if not os.path.exists("build/mbedtls-host/libmbedtls_host.a"):
    msrcs = sorted(glob.glob(os.path.join(mbed, "library/*.c")))
    def comp_mh(s):
        o = os.path.join("build/mbedtls-host", os.path.basename(s)[:-2] + ".o")
        subprocess.run(
            zig + ["cc", "-target", "x86_64-linux-musl", "-Os",
                   "-ffunction-sections", "-fdata-sections",
                   "-DMBEDTLS_THREADING_C", "-DMBEDTLS_THREADING_PTHREAD",
                   f"-I{mbed}/include", f"-I{mbed}/library", "-c", s, "-o", o],
            check=True,
        )
        return o
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        mobjs = list(ex.map(comp_mh, msrcs))
    subprocess.run(zig + ["ar", "rcs", "build/mbedtls-host/libmbedtls_host.a", *mobjs], check=True)
if not os.path.exists("build/bearssl-host/libbearssl_host.a"):
    bsrcs = sorted(
        s for s in glob.glob(os.path.join(bear, "src/**/*.c"), recursive=True)
        if not os.path.basename(s).startswith("ec_prime_i31_secp")
    )
    def comp_bh(idx_s):
        idx, s = idx_s
        o = os.path.join("build/bearssl-host", f"{idx:03d}_{os.path.basename(s)[:-2]}.o")
        subprocess.run(
            zig + ["cc", "-target", "x86_64-linux-musl", "-Os",
                   "-ffunction-sections", "-fdata-sections",
                   f"-I{bear}/inc", f"-I{bear}/src", "-c", s, "-o", o],
            check=True,
        )
        return o
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        bobjs = list(ex.map(comp_bh, enumerate(bsrcs)))
    subprocess.run(zig + ["ar", "rcs", "build/bearssl-host/libbearssl_host.a", *bobjs], check=True)
PYEOF
fi
$ZIG_CMD cc -target x86_64-linux-musl -Os -s -static -Wall -Wextra -Wno-unused-parameter \
    -ffunction-sections -fdata-sections -Wl,--gc-sections \
    -DMBEDTLS_THREADING_C -DMBEDTLS_THREADING_PTHREAD \
    -I"$MBEDTLS_DIR/include" -I"$BEARSSL_DIR/inc" \
    native/arc7vpn.c \
    build/mbedtls-host/libmbedtls_host.a \
    build/bearssl-host/libbearssl_host.a \
    -lpthread \
    -o build/native/host/arc7vpn

POCKETPY_DIR="${POCKETPY_DIR:-/tmp/pocketpy-src}"
if [ ! -d "$POCKETPY_DIR" ]; then
    git clone --depth 1 --branch v1.4.6 https://github.com/pocketpy/pocketpy.git "$POCKETPY_DIR"
fi

PKPY_SRCS=$(python3 -c 'import glob,sys; print(" ".join(s for s in sorted(glob.glob(sys.argv[1]+"/src/*.cpp")) if not s.endswith("pocketpy_c.c")))' "$POCKETPY_DIR")
$ZIG_CMD c++ -target arm-linux-musleabi -march=armv7-a -mcpu=cortex_a8 -std=c++17 \
    -Os -s -static -Wno-date-time -ffunction-sections -fdata-sections -Wl,--gc-sections \
    -I"$POCKETPY_DIR/include" \
    native/arc7py3.cpp $PKPY_SRCS \
    -lpthread \
    -o build/native/armeabi-v7a/libarc7py3.so

$ZIG_CMD cc $CFLAGS_ARM -ffunction-sections -fdata-sections -Wl,--gc-sections \
    -DHAVE_UNISTD_H=1 -DHAVE_STDARG_H=1 \
    -DMBEDTLS_THREADING_C -DMBEDTLS_THREADING_PTHREAD \
    -I"$MBEDTLS_DIR/include" -I"$ZLIB_DIR" \
    native/arc7apt.c \
    "$ZLIB_DIR"/adler32.c "$ZLIB_DIR"/crc32.c \
    "$ZLIB_DIR"/inffast.c "$ZLIB_DIR"/inflate.c \
    "$ZLIB_DIR"/inftrees.c "$ZLIB_DIR"/zutil.c \
    build/mbedtls-arm/libmbedtls_all.a \
    -lpthread \
    -o build/native/armeabi-v7a/libarc7apt.so

cp build/native/armeabi-v7a/*.so build/native/armeabi/


# Companion stores run their OWN TLS processes. Build their engine first, then inject it
# into F-Droid; fresh builds must never require a previously installed suite gateway.
mkdir -p build/companion-tls/assets/cacerts build/companion-tls/lib/armeabi-v7a build/companion-tls/lib/armeabi
cp -f app/src/main/assets/cacerts/ca-bundle-2026.crt build/companion-tls/assets/cacerts/
cp -f build/native/armeabi-v7a/libarc7tls.so build/companion-tls/lib/armeabi-v7a/
cp -f build/native/armeabi/libarc7tls.so build/companion-tls/lib/armeabi/

mkdir -p app/src/main/assets/starter-apps
FDROID_RAW="/tmp/fdroid-upstream-raw.apk"
FDROID_APK="app/src/main/assets/starter-apps/FDroidClassicLegacy.apk"
FDROID_URL="https://github.com/Smu1zel/fdroidclassiclegacy/releases/download/v1.3-beta2-PATCH/app-debug.apk"
if [ ! -s "$FDROID_RAW" ]; then
    echo "    Fetching upstream F-Droid Classic Legacy base APK..."
    if ! curl -fsSL "$FDROID_URL" -o "$FDROID_RAW" 2>/dev/null; then
        cp -f "$FDROID_APK" "$FDROID_RAW"
    fi
fi
cp -f "$FDROID_RAW" "$FDROID_APK"
"$PY_BIN" scripts/patch-fdroid-apk.py \
    "$FDROID_APK" \
    "$ZIPALIGN" \
    "$JAVA_BIN" \
    "$APKSIGNER_JAR" \
    "$KEYSTORE_PATH" \
    "$KEYSTORE_PASS" \
    "$KEYSTORE_ALIAS"
# Fail the installer build if it would bundle an upstream/stale F-Droid APK instead of the
# result of the current patch step (disk-backed two-pass index parser + heap-safe updates).
"$PY_BIN" - "$FDROID_APK" <<'PYFDROIDCHECK'
import sys, zipfile
apk = sys.argv[1]
markers = (b"processIndexV1Streaming", b"dl-arc7-index-v1-", b"512 MiB safety limit")
with zipfile.ZipFile(apk, "r") as zf:
    dex_names = sorted(n for n in zf.namelist()
                       if n == "classes.dex" or (n.startswith("classes") and n.endswith(".dex")))
    if not dex_names:
        raise SystemExit("F-Droid patch check failed: no DEX files in APK")
    dex_files = [zf.read(n) for n in dex_names]
missing = [m.decode("ascii") for m in markers if not any(m in dex for dex in dex_files)]
if missing:
    raise SystemExit("F-Droid patch check failed; installer would bundle unpatched APK: " + ", ".join(missing))
print("Verified installer F-Droid payload has the low-memory streaming patch:", apk)
PYFDROIDCHECK
ls -lh "$FDROID_APK"

# ------------------------------------------------------------------------------
# Standalone "truly separate app" builder (v2.6.9)
#
# Every custom app that used to be just an extra <activity> inside the suite package is now
# built as its OWN signed APK with its own package id, launcher label/icon and task stack:
#   releases/YouTube.apk        -> ca.arc7freedom.youtube
#   releases/YouTubeMusic.apk   -> ca.arc7freedom.ytmusic
#
# The Java classes stay in `ca.arc7freedom.app` (shared engine), so `--custom-package`
# keeps the generated `R` class where every source file expects it.
# ------------------------------------------------------------------------------
build_standalone_app() {
    local key="$1" manifest="$2" out_apk="$3"
    local work="build/split-$key"
    rm -rf "$work"
    mkdir -p "$work/res_compiled" "$work/gen" "$work/classes" "$work/dex" \
             "$work/assets/cacerts" "$work/stage/lib/armeabi-v7a" "$work/stage/lib/armeabi"

    cp -f app/src/main/assets/cacerts/ca-bundle-2026.crt "$work/assets/cacerts/"
    cp -f app/src/main/assets/cacerts/manifest.tsv "$work/assets/cacerts/"
    cp -R app/src/main/assets/licenses "$work/assets/"
    cp -R app/src/main/assets/audio-self-test "$work/assets/"
    cp -R app/src/main/assets/lan-share "$work/assets/"
    if [ -d app/src/main/assets/cacerts/android-hashed ]; then
        mkdir -p "$work/assets/cacerts/android-hashed"
        cp -f app/src/main/assets/cacerts/android-hashed/*.0 "$work/assets/cacerts/android-hashed/" 2>/dev/null || true
    fi

    "$AAPT2" compile --dir app/src/main/res -o "$work/res_compiled/res.zip"
    "$AAPT2" link \
        -I "$ANDROID_JAR" \
        --manifest "$manifest" \
        --custom-package ca.arc7freedom.app \
        -A "$work/assets" \
        --java "$work/gen" \
        --min-sdk-version 15 \
        --target-sdk-version 17 \
        --no-compile-sdk-metadata \
        -o "$work/unsigned.apk" \
        "$work/res_compiled/res.zip"

    compile_java_and_dex \
        "$work/classes" \
        "$work/dex" \
        "$work/gen/ca/arc7freedom/app/R.java" \
        app/src/main/java/ca/arc7freedom/app/*.java \
        $(find app/src/main/java/com/google/zxing -name "*.java" -print | LC_ALL=C sort)

    cp -f build/native/armeabi-v7a/libarc7tls.so "$work/stage/lib/armeabi-v7a/"
    cp -f build/native/armeabi/libarc7tls.so "$work/stage/lib/armeabi/"

    (cd "$work/dex" && zip -qj ../unsigned.apk classes.dex)
    if [ -d "$work/stage/lib" ]; then
        (cd "$work/stage" && zip -qr -0 ../unsigned.apk lib 2>/dev/null || true)
    fi

    "$ZIPALIGN" -f -p 4 "$work/unsigned.apk" "$work/aligned.apk"
    "$JAVA_BIN" -jar "$APKSIGNER_JAR" sign \
        --ks "$KEYSTORE_PATH" \
        --ks-pass "pass:$KEYSTORE_PASS" \
        --key-pass "pass:$KEYSTORE_PASS" \
        --ks-key-alias "$KEYSTORE_ALIAS" \
        --min-sdk-version 15 \
        --v1-signing-enabled true \
        --v2-signing-enabled true \
        --v3-signing-enabled true \
        --out "$out_apk" \
        "$work/aligned.apk"
    ls -lh "$out_apk"
}

build_default_split_apps() {
    # 32 separately installable default apps (Browser, Clock, Calculator, …).
    #
    # The shared Java sources are compiled to ONE `classes.dex`; every app then only pays for
    # one `aapt2 link` + zip + signature, so publishing all 32 stays fast.
    local repo_root="$PWD"
    local work="build/split-defaults"
    rm -rf "$work"
    mkdir -p "$work/res_compiled" "$work/gen" "$work/classes" "$work/dex" \
             "$work/assets/cacerts" "$work/stage/lib/armeabi-v7a" "$work/stage/lib/armeabi" \
             releases/apps

    # A cached build must not retain retired APKs in globbed checksums/releases.
    "$PY_BIN" - <<'PRUNE_APPS'
from pathlib import Path
expected = {line.split("\t")[1] for line in Path("split-apps/apps.tsv").read_text().splitlines() if line}
for path in Path("releases/apps").glob("*.apk"):
    if path.name not in expected:
        path.unlink()
        print("Removed retired generated APK:", path.name)
PRUNE_APPS

    cp -f app/src/main/assets/cacerts/ca-bundle-2026.crt "$work/assets/cacerts/"
    cp -f app/src/main/assets/cacerts/manifest.tsv "$work/assets/cacerts/"
    cp -R app/src/main/assets/licenses "$work/assets/"
    cp -R app/src/main/assets/audio-self-test "$work/assets/"
    cp -R app/src/main/assets/lan-share "$work/assets/"
    if [ -d app/src/main/assets/cacerts/android-hashed ]; then
        mkdir -p "$work/assets/cacerts/android-hashed"
        cp -f app/src/main/assets/cacerts/android-hashed/*.0 "$work/assets/cacerts/android-hashed/" 2>/dev/null || true
    fi

    "$AAPT2" compile --dir app/src/main/res -o "$work/res_compiled/res.zip"

    # Link once to generate `R.java` for the shared `ca.arc7freedom.app` classes.
    local seed_manifest="split-apps/calculator/AndroidManifest.xml"
    "$AAPT2" link \
        -I "$ANDROID_JAR" \
        --manifest "$seed_manifest" \
        --custom-package ca.arc7freedom.app \
        -A "$work/assets" \
        --java "$work/gen" \
        --min-sdk-version 15 \
        --target-sdk-version 17 \
        --no-compile-sdk-metadata \
        -o "$work/seed.apk" \
        "$work/res_compiled/res.zip"

    compile_java_and_dex \
        "$work/classes" \
        "$work/dex" \
        "$work/gen/ca/arc7freedom/app/R.java" \
        app/src/main/java/ca/arc7freedom/app/*.java \
        $(find app/src/main/java/com/google/zxing -name "*.java" -print | LC_ALL=C sort)

    cp -f build/native/armeabi-v7a/libarc7tls.so "$work/stage/lib/armeabi-v7a/"
    cp -f build/native/armeabi/libarc7tls.so "$work/stage/lib/armeabi/"

    # Apps that actually speak TLS need the local `libarc7tls.so` gateway + the 2026 CA store;
    # Calculator / Notes / Photos / … stay tiny without them.
    local network_apps
    network_apps=" $("$PY_BIN" -c 'import runpy; print(" ".join(sorted(runpy.run_path("scripts/gen-split-apps.py")["NETWORK_APPS"])))') "
    local empty_assets="$work/empty_assets"
    mkdir -p "$empty_assets"
    cp -R app/src/main/assets/licenses "$empty_assets/"
    # Non-network audio owners (notably Voice Recorder) still expose local sound tests.
    # Keep the clips offline without adding TLS binaries, CAs or Internet permission.
    cp -R app/src/main/assets/audio-self-test "$empty_assets/"

    local built=0
    while IFS=$'\t' read -r key fname label desc; do
        [ -n "$key" ] || continue
        local manifest="split-apps/$key/AndroidManifest.xml"
        [ -f "$manifest" ] || { echo "ERROR: Missing $manifest"; return 1; }
        rm -f "$work/app-unsigned.apk" "$work/app-aligned.apk"
        local assets_dir="$work/assets"
        local with_libs=1
        case "$network_apps" in
            *" $key "*) ;;
            *) assets_dir="$empty_assets"; with_libs=0 ;;
        esac
        "$AAPT2" link \
            -I "$ANDROID_JAR" \
            --manifest "$manifest" \
            --custom-package ca.arc7freedom.app \
            -A "$assets_dir" \
            --min-sdk-version 15 \
            --target-sdk-version 17 \
            --no-compile-sdk-metadata \
            -o "$work/app-unsigned.apk" \
            "$work/res_compiled/res.zip"
        (cd "$work/dex" && zip -qj "$repo_root/$work/app-unsigned.apk" classes.dex)
        if [ "$with_libs" -eq 1 ]; then
            (cd "$work/stage" && zip -qr -0 "$repo_root/$work/app-unsigned.apk" lib)
        fi
        local helper
        for helper in $("$PY_BIN" -c 'import sys; print("libarc7vpn.so" if sys.argv[1] == "vpn" else "libarc7py3.so libarc7apt.so libarc7root.so libarc7su.so libarc7tool.so" if sys.argv[1] == "terminal" else "")' "$key"); do
            for abi in armeabi-v7a armeabi; do
                mkdir -p "$work/helpers/lib/$abi"
                cp -f "build/native/$abi/$helper" "$work/helpers/lib/$abi/"
                (cd "$work/helpers" && zip -q -0 "$repo_root/$work/app-unsigned.apk" "lib/$abi/$helper")
            done
        done
        "$ZIPALIGN" -f -p 4 "$work/app-unsigned.apk" "$work/app-aligned.apk"
        "$JAVA_BIN" -jar "$APKSIGNER_JAR" sign \
            --ks "$KEYSTORE_PATH" \
            --ks-pass "pass:$KEYSTORE_PASS" \
            --key-pass "pass:$KEYSTORE_PASS" \
            --ks-key-alias "$KEYSTORE_ALIAS" \
            --min-sdk-version 15 \
            --v1-signing-enabled true \
            --v2-signing-enabled true \
            --v3-signing-enabled true \
            --v4-signing-enabled false \
            --out "releases/apps/$fname" \
            "$work/app-aligned.apk"
        rm -f "releases/apps/$fname.idsig"
        built=$((built + 1))
    done < split-apps/apps.tsv
    echo "    Built $built standalone default apps into releases/apps/"
    ls -lh releases/apps | tail -n +2 | head -30
}

echo "==> [3/8] Building standalone Stock Android 4.2 Home Launcher v56 (Glass & Widgets) & Aurora Store (com.aurora.store)..."
rm -rf build/launcher && mkdir -p build/launcher/{res_compiled,gen,classes,dex}
"$AAPT2" compile --dir launcher-android4/src/main/res -o build/launcher/res_compiled/res.zip
"$AAPT2" link \
    -I "$ANDROID_JAR" \
    --manifest launcher-android4/src/main/AndroidManifest.xml \
    --java build/launcher/gen \
    --min-sdk-version 15 \
    --target-sdk-version 17 \
    --no-compile-sdk-metadata \
    -o build/launcher/unsigned.apk \
    build/launcher/res_compiled/res.zip

compile_java_and_dex \
    build/launcher/classes \
    build/launcher/dex \
    build/launcher/gen/ca/arc7freedom/launcher/R.java \
    launcher-android4/src/main/java/ca/arc7freedom/launcher/*.java

(cd build/launcher/dex && zip -qj ../unsigned.apk classes.dex)
"$ZIPALIGN" -f -p 4 build/launcher/unsigned.apk build/launcher/aligned.apk
"$JAVA_BIN" -jar "$APKSIGNER_JAR" sign \
    --ks "$KEYSTORE_PATH" \
    --ks-pass "pass:$KEYSTORE_PASS" \
    --key-pass "pass:$KEYSTORE_PASS" \
    --ks-key-alias "$KEYSTORE_ALIAS" \
    --min-sdk-version 15 \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out releases/VanillaAndroid4Home.apk \
    build/launcher/aligned.apk

rm -rf build/aurora && mkdir -p build/aurora/{res_compiled,gen,classes,dex}
"$AAPT2" compile --dir aurora-store/src/main/res -o build/aurora/res_compiled/res.zip
"$AAPT2" link \
    -I "$ANDROID_JAR" \
    --manifest aurora-store/src/main/AndroidManifest.xml \
    -A build/companion-tls/assets \
    --java build/aurora/gen \
    --min-sdk-version 15 \
    --target-sdk-version 17 \
    --no-compile-sdk-metadata \
    -o build/aurora/unsigned.apk \
    build/aurora/res_compiled/res.zip

compile_java_and_dex \
    build/aurora/classes \
    build/aurora/dex \
    build/aurora/gen/com/aurora/store/R.java \
    aurora-store/src/main/java/com/aurora/store/*.java \
    common/src/main/java/ca/arc7freedom/net/*.java

(cd build/aurora/dex && zip -qj ../unsigned.apk classes.dex)
(cd build/companion-tls && zip -qr -0 ../aurora/unsigned.apk lib)
"$ZIPALIGN" -f -p 4 build/aurora/unsigned.apk build/aurora/aligned.apk
"$JAVA_BIN" -jar "$APKSIGNER_JAR" sign \
    --ks "$KEYSTORE_PATH" \
    --ks-pass "pass:$KEYSTORE_PASS" \
    --key-pass "pass:$KEYSTORE_PASS" \
    --ks-key-alias "$KEYSTORE_ALIAS" \
    --min-sdk-version 15 \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out releases/AuroraStore.apk \
    build/aurora/aligned.apk

echo "==> [3b/8] Building TRULY SEPARATE standalone apps (ca.arc7freedom.youtube & ca.arc7freedom.ytmusic)..."
build_standalone_app "youtube" "split-apps/youtube/AndroidManifest.xml" "releases/YouTube.apk"
build_standalone_app "ytmusic" "split-apps/ytmusic/AndroidManifest.xml" "releases/YouTubeMusic.apk"
mkdir -p app/src/main/assets/starter-apps
cp -f releases/YouTube.apk app/src/main/assets/starter-apps/YouTube.apk
cp -f releases/YouTubeMusic.apk app/src/main/assets/starter-apps/YouTubeMusic.apk

echo "==> [3c/9] Building 32 separately installable default apps (releases/apps/*.apk)..."
"$PY_BIN" scripts/build-qr-companion.py
"$PY_BIN" scripts/build-windows-tools.py
build_default_split_apps

mkdir -p app/src/main/assets/starter-apps
cp -f app/src/main/res/drawable-mdpi/ic_vlc.png vlc-player/src/main/res/drawable-mdpi/ic_vlc.png
cp -f app/src/main/res/drawable-hdpi/ic_vlc.png vlc-player/src/main/res/drawable-hdpi/ic_vlc.png
cp -f app/src/main/res/drawable-xhdpi/ic_vlc.png vlc-player/src/main/res/drawable-xhdpi/ic_vlc.png
cp -f app/src/main/res/drawable-xxhdpi/ic_vlc.png vlc-player/src/main/res/drawable-xxhdpi/ic_vlc.png
set +e
"$PY_BIN" scripts/fetch-vlc-apk.py \
    "app/src/main/assets/starter-apps/VLC.apk" \
    "releases/VLC.apk" \
    "$ZIPALIGN" \
    "$JAVA_BIN" \
    "$APKSIGNER_JAR" \
    "$KEYSTORE_PATH" \
    "$KEYSTORE_PASS" \
    "$KEYSTORE_ALIAS"
VLC_RC=$?
set -e
if [ "$VLC_RC" -ne 0 ]; then
    rm -rf build/vlc && mkdir -p build/vlc/{res_compiled,gen,classes,dex}
    "$AAPT2" compile --dir vlc-player/src/main/res -o build/vlc/res_compiled/res.zip
    "$AAPT2" link \
        -I "$ANDROID_JAR" \
        --manifest vlc-player/src/main/AndroidManifest.xml \
        -A build/companion-tls/assets \
        --java build/vlc/gen \
        --min-sdk-version 15 \
        --target-sdk-version 17 \
        --no-compile-sdk-metadata \
        -o build/vlc/unsigned.apk \
        build/vlc/res_compiled/res.zip
    compile_java_and_dex \
        build/vlc/classes \
        build/vlc/dex \
        build/vlc/gen/org/videolan/vlc/R.java \
        vlc-player/src/main/java/org/videolan/vlc/*.java \
        common/src/main/java/ca/arc7freedom/net/*.java
    (cd build/vlc/dex && zip -qj ../unsigned.apk classes.dex)
    (cd build/companion-tls && zip -qr -0 ../vlc/unsigned.apk lib)
    "$ZIPALIGN" -f -p 4 build/vlc/unsigned.apk build/vlc/aligned.apk
    "$JAVA_BIN" -jar "$APKSIGNER_JAR" sign \
        --ks "$KEYSTORE_PATH" \
        --ks-pass "pass:$KEYSTORE_PASS" \
        --key-pass "pass:$KEYSTORE_PASS" \
        --ks-key-alias "$KEYSTORE_ALIAS" \
        --min-sdk-version 15 \
        --v1-signing-enabled true \
        --v2-signing-enabled true \
        --v3-signing-enabled true \
        --out releases/VLC.apk \
        build/vlc/aligned.apk
    cp -f releases/VLC.apk app/src/main/assets/starter-apps/VLC.apk
fi
cp -f releases/AuroraStore.apk app/src/main/assets/starter-apps/AuroraStore.apk

mkdir -p app/src/main/assets/chromebook
cp -f tools/Start-Chromebook-Wired-Internet.sh app/src/main/assets/chromebook/Start-Chromebook-Wired-Internet.sh
cp -f tools/Start-Kobo-Second-Screen.py app/src/main/assets/chromebook/Start-Kobo-Second-Screen.py
cp -f tools/Start-PC-Stream-Deck.py app/src/main/assets/chromebook/Start-PC-Stream-Deck.py
cp -f tools/Start-PC-Stream-Deck.bat app/src/main/assets/chromebook/Start-PC-Stream-Deck.bat
cp -f tools/Arc7-Windows.ps1 app/src/main/assets/chromebook/Arc7-Windows.ps1
cp -f tools/Start-PC-Stream-Deck.sh app/src/main/assets/chromebook/Start-PC-Stream-Deck.sh
cp -f tools/Start-PC-School-VPN-Relay.py app/src/main/assets/chromebook/Start-PC-School-VPN-Relay.py
cp -f releases/VanillaAndroid4Home.apk app/src/main/assets/VanillaAndroid4Home.apk

# One offline, hash-verified catalog for every install path, including all 32 defaults.
# Shared ZIP-entry bytes are stored once; installed APKs remain fully independent.
"$PY_BIN" scripts/bundle-maintenance.py

echo "==> [4/8] Compiling & packaging KoboArc7Freedom.apk v2.6.9 (2026 DASH Remux Playback + Separate YouTube & YouTube Music Apps + Widget Removal Fix + Standby Now-Playing + Tailscale Google Auth + Fixed F-Droid v1306 (largeHeap + Throwable sync catch) + Launcher v56 + VLC)..."
rm -rf build/app && mkdir -p build/app/{res_compiled,gen,classes,dex,stage/lib/armeabi-v7a,stage/lib/armeabi}
"$AAPT2" compile --dir app/src/main/res -o build/app/res_compiled/res.zip
"$AAPT2" link \
    -I "$ANDROID_JAR" \
    --manifest app/src/main/AndroidManifest.xml \
    -A app/src/main/assets \
    -0 .apk \
    --java build/app/gen \
    --min-sdk-version 15 \
    --target-sdk-version 17 \
    --no-compile-sdk-metadata \
    -o build/app/unsigned.apk \
    build/app/res_compiled/res.zip

compile_java_and_dex \
    build/app/classes \
    build/app/dex \
    build/app/gen/ca/arc7freedom/app/R.java \
    app/src/main/java/ca/arc7freedom/app/*.java \
        $(find app/src/main/java/com/google/zxing -name "*.java" -print | LC_ALL=C sort)

cp build/native/armeabi-v7a/*.so build/app/stage/lib/armeabi-v7a/
cp build/native/armeabi/*.so build/app/stage/lib/armeabi/
(cd build/app/dex && zip -qj ../unsigned.apk classes.dex)
(cd build/app/stage && zip -qr -0 ../unsigned.apk lib)

echo "==> [5/8] Aligning & signing KoboArc7Freedom.apk with permanent certificate (v1 + v2 + v3)..."
"$ZIPALIGN" -f -p 4 build/app/unsigned.apk build/app/aligned.apk
"$JAVA_BIN" -jar "$APKSIGNER_JAR" sign \
    --ks "$KEYSTORE_PATH" \
    --ks-pass "pass:$KEYSTORE_PASS" \
    --key-pass "pass:$KEYSTORE_PASS" \
    --ks-key-alias "$KEYSTORE_ALIAS" \
    --min-sdk-version 15 \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out releases/KoboArc7Freedom.apk \
    build/app/aligned.apk

echo "==> [6/8] Building data-preserving in-place Root Upgrader (releases/Upgrade-My-Kobo-To-v2.6.9.apk)..."
rm -rf build/upgrader && mkdir -p build/upgrader/{res_compiled,gen,classes,dex,assets}
cp -f releases/KoboArc7Freedom.apk build/upgrader/assets/KoboArc7Freedom.apk
"$AAPT2" compile --dir upgrader/src/main/res -o build/upgrader/res_compiled/res.zip
"$AAPT2" link \
    -I "$ANDROID_JAR" \
    --manifest upgrader/src/main/AndroidManifest.xml \
    -A build/upgrader/assets \
    -0 .apk \
    --java build/upgrader/gen \
    --min-sdk-version 15 \
    --target-sdk-version 17 \
    --no-compile-sdk-metadata \
    -o build/upgrader/unsigned.apk \
    build/upgrader/res_compiled/res.zip

compile_java_and_dex \
    build/upgrader/classes \
    build/upgrader/dex \
    build/upgrader/gen/ca/arc7freedom/upgrader/R.java \
    upgrader/src/main/java/ca/arc7freedom/upgrader/*.java \
    app/src/main/java/ca/arc7freedom/app/BundledApk.java

(cd build/upgrader/dex && zip -qj ../unsigned.apk classes.dex)
"$ZIPALIGN" -f -p 4 build/upgrader/unsigned.apk build/upgrader/aligned.apk
# Keep exactly this release's upgrader, including when updating from another 2.6.x build.
for stale_upgrader in releases/Upgrade-My-Kobo-To-v*.apk; do
    if [ "$stale_upgrader" != "releases/Upgrade-My-Kobo-To-v2.6.9.apk" ]; then
        rm -f -- "$stale_upgrader"
    fi
done
"$JAVA_BIN" -jar "$APKSIGNER_JAR" sign \
    --ks "$KEYSTORE_PATH" \
    --ks-pass "pass:$KEYSTORE_PASS" \
    --key-pass "pass:$KEYSTORE_PASS" \
    --ks-key-alias "$KEYSTORE_ALIAS" \
    --min-sdk-version 15 \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out releases/Upgrade-My-Kobo-To-v2.6.9.apk \
    build/upgrader/aligned.apk
cp -f app/src/main/assets/starter-apps/FDroidClassicLegacy.apk releases/FDroidClassicLegacy.apk

echo "==> [7/8] Generating local Wi-Fi portal & writing SHA256SUMS.txt..."
rm -rf docs
rm -f releases/*.idsig app/src/main/assets/starter-apps/*.idsig
chmod +x tools/Backup-Kobo-To-Laptop-ADB.sh tools/Start-Chromebook-Wired-Internet.sh tools/Start-Kobo-Second-Screen.py tools/Start-PC-Stream-Deck.py tools/Start-PC-Stream-Deck.sh tools/Start-PC-School-VPN-Relay.py tools/serve-over-wifi.sh
"$PY_BIN" tools/serve-over-wifi.py --write-static
(cd releases && sha256sum \
    Upgrade-My-Kobo-To-v2.6.9.apk \
    KoboArc7Freedom.apk \
    YouTube.apk \
    YouTubeMusic.apk \
    VLC.apk \
    AuroraStore.apk \
    FDroidClassicLegacy.apk \
    VanillaAndroid4Home.apk \
    logo-banner.png \
    index.html > SHA256SUMS.txt)
(cd tools && sha256sum \
    Start-PC-Stream-Deck.py \
    Start-PC-Stream-Deck.bat \
    Start-PC-Stream-Deck.sh \
    Start-PC-School-VPN-Relay.py \
    Start-Chromebook-Wired-Internet.sh \
    Start-Kobo-Second-Screen.py \
    Backup-Kobo-To-Laptop-ADB.bat \
    Backup-Kobo-To-Laptop-ADB.sh \
    Start-Kobo-WiFi-Server.bat \
    Start-Kobo-WiFi-Server.sh \
    qr-transfer.html \
    Arc7-Windows.ps1 \
    Windows-Tools.zip \
    WINDOWS-TOOLS.md \
    usb-sideload-and-root.bat \
    serve-over-wifi.bat) >> releases/SHA256SUMS.txt
(cd releases/apps && sha256sum *.apk > SHA256SUMS.txt)
# Fold the 32 standalone-app hashes into the top-level checksum file so a single
# releases/latest/download/SHA256SUMS.txt verifies the whole release.
(cd releases/apps && sha256sum *.apk) >> releases/SHA256SUMS.txt
python3 - <<'PYCHECK'
import hashlib, os
root = "releases"
sums = {}
for line in open(os.path.join(root, "SHA256SUMS.txt"), encoding="utf-8"):
    h, name = line.split(None, 1)
    sums[name.strip()] = h
bad = []
for name in sorted(os.listdir(os.path.join(root, "apps"))):
    if not name.endswith(".apk"):
        continue
    h = hashlib.sha256(open(os.path.join(root, "apps", name), "rb").read()).hexdigest()
    if sums.get(name) != h:
        bad.append(name)
if bad:
    raise SystemExit(f"checksum mismatch for {bad}")
print(f"Verified {len(sums)} release checksums (incl. 32 standalone apps)")
PYCHECK

cat releases/SHA256SUMS.txt
ls -lh \
    releases/Upgrade-My-Kobo-To-v2.6.9.apk \
    releases/KoboArc7Freedom.apk \
    releases/YouTube.apk \
    releases/YouTubeMusic.apk \
    releases/VLC.apk \
    releases/AuroraStore.apk \
    releases/FDroidClassicLegacy.apk \
    releases/VanillaAndroid4Home.apk \
    releases/logo-banner.png \
    tools/Start-PC-Stream-Deck.py \
    tools/Start-PC-Stream-Deck.bat \
    tools/Start-PC-Stream-Deck.sh \
    tools/Start-PC-School-VPN-Relay.py \
    tools/Start-Chromebook-Wired-Internet.sh \
    tools/Start-Kobo-Second-Screen.py \
    tools/Backup-Kobo-To-Laptop-ADB.bat \
    tools/Backup-Kobo-To-Laptop-ADB.sh \
    tools/Start-Kobo-WiFi-Server.bat \
    tools/Start-Kobo-WiFi-Server.sh \
    releases/index.html
