#!/usr/bin/env bash
# ==============================================================================
# Kobo Arc 7 Freedom v1.8.0 — Reproducible Full Native + Dalvik (DEX 035) Build
#
# Builds BOTH signed Android 4.2.2 (API 17 / minSdk 15) APKs + Patched F-Droid:
#   1. Pre-packages all 120 modern 2026 Mozilla Root CA certificates offline
#      into `app/src/main/assets/cacerts/` (`<subject_hash_old>.<idx>` + bundle)
#   2. Patches `FDroidClassicLegacy.apk` binary `resources.arsc` (`default_repos`
#      -> `http://127.0.0.1:8118/__https/...` + `F-Droid Archive` enabled) &
#      signs with `arc7freedom.jks` (`v1+v2+v3` valid at API 14/15/17!)
#   3. Cross-compiles all 4 static ARMv7 EABI5 native binaries:
#        - `libarc7su.so`   (Setuid + daemon fallback `su`)
#        - `libarc7root.so` (Multi-stage Linux 3.4 ARMv7 kernel root engine)
#        - `libarc7tool.so` (Streaming zlib + FIPS-180-4 SHA-256 partition tool)
#        - `libarc7tls.so`  (BearSSL TLS 1.0–1.2 local server + Mbed-TLS 3.6 LTS
#                            TLS 1.3 upstream + `/__https/` F-Droid bridge + NTP)
#   4. Builds & v1/v2/v3 signs `releases/VanillaAndroid4Home.apk` (v48 Widgets)
#      & `releases/KoboArc7Freedom.apk` (`v1.8.0`, code `180`) + `SHA256SUMS.txt`
# ==============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
LATEST_BUILD_TOOLS=""
if [ -n "$SDK_ROOT" ] && [ -d "$SDK_ROOT/build-tools" ]; then
    LATEST_BUILD_TOOLS="$(ls -1d "$SDK_ROOT"/build-tools/* 2>/dev/null | sort -V | tail -n 1 || true)"
fi

if [ -d "/opt/toolchain/lib64" ]; then
    export LD_LIBRARY_PATH="/opt/toolchain/lib64:${LD_LIBRARY_PATH:-}"
fi

# Resolve JDK tools
if [ -x "/opt/toolchain/jdk/bin/java" ]; then
    JAVA_BIN="${JAVA:-/opt/toolchain/jdk/bin/java}"
    JAVAC_BIN="${JAVAC:-/opt/toolchain/jdk/bin/javac}"
    KEYTOOL_BIN="${KEYTOOL:-/opt/toolchain/jdk/bin/keytool}"
else
    JAVA_BIN="${JAVA:-java}"
    JAVAC_BIN="${JAVAC:-javac}"
    KEYTOOL_BIN="${KEYTOOL:-keytool}"
fi

# Resolve Android SDK tools (aapt2, zipalign, android.jar, d8/dfoda, apksigner)
if [ -x "/opt/toolchain/bin/aapt2" ]; then
    AAPT2="${AAPT2:-/opt/toolchain/bin/aapt2}"
    ZIPALIGN="${ZIPALIGN:-/opt/toolchain/bin/zipalign}"
elif [ -n "$LATEST_BUILD_TOOLS" ]; then
    AAPT2="${AAPT2:-$LATEST_BUILD_TOOLS/aapt2}"
    ZIPALIGN="${ZIPALIGN:-$LATEST_BUILD_TOOLS/zipalign}"
else
    AAPT2="${AAPT2:-aapt2}"
    ZIPALIGN="${ZIPALIGN:-zipalign}"
fi

if [ -f "/opt/toolchain/sdk/platforms/android-17/android.jar" ]; then
    ANDROID_JAR="${ANDROID_JAR:-/opt/toolchain/sdk/platforms/android-17/android.jar}"
elif [ -n "$SDK_ROOT" ] && [ -f "$SDK_ROOT/platforms/android-17/android.jar" ]; then
    ANDROID_JAR="${ANDROID_JAR:-$SDK_ROOT/platforms/android-17/android.jar}"
else
    echo "ERROR: android-17/android.jar not found. Run ./scripts/bootstrap-toolchain.sh or install platforms;android-17."
    exit 1
fi

D8_BIN=""
D8_JAR=""
if [ -x "/opt/toolchain/sdk/build-tools/34.0.0/d8" ]; then
    D8_BIN="/opt/toolchain/sdk/build-tools/34.0.0/d8"
elif [ -n "$LATEST_BUILD_TOOLS" ] && [ -x "$LATEST_BUILD_TOOLS/d8" ]; then
    D8_BIN="$LATEST_BUILD_TOOLS/d8"
elif [ -f "/opt/toolchain/jars/d8.jar" ]; then
    D8_JAR="/opt/toolchain/jars/d8.jar"
fi

if [ -f "/opt/toolchain/jars/ecj-3.45.0.jar" ]; then
    ECJ="${ECJ:-/opt/toolchain/jars/ecj-3.45.0.jar}"
else
    ECJ="${ECJ:-/opt/toolchain/jars/ecj-4.8.jar}"
fi
DFODA="${DFODA:-/opt/toolchain/jars/dfoda0.0.9.jar}"

if [ -f "/opt/toolchain/jars/apksigner.jar" ]; then
    APKSIGNER_JAR="${APKSIGNER:-/opt/toolchain/jars/apksigner.jar}"
elif [ -f "/opt/toolchain/sdk/build-tools/34.0.0/lib/apksigner.jar" ]; then
    APKSIGNER_JAR="${APKSIGNER:-/opt/toolchain/sdk/build-tools/34.0.0/lib/apksigner.jar}"
elif [ -n "$LATEST_BUILD_TOOLS" ] && [ -f "$LATEST_BUILD_TOOLS/lib/apksigner.jar" ]; then
    APKSIGNER_JAR="${APKSIGNER:-$LATEST_BUILD_TOOLS/lib/apksigner.jar}"
else
    echo "ERROR: apksigner.jar not found."
    exit 1
fi

# Resolve Python & Zig C cross-compiler
if [ -x "/opt/toolchain/venv/bin/python" ]; then
    PY_BIN="/opt/toolchain/venv/bin/python"
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

echo "==> [1/7] Pre-packaging offline 2026 CAs, patching F-Droid Classic TLS 1.3 Index Bridge & icons..."
"$PY_BIN" scripts/generate-cacerts.py
"$PY_BIN" scripts/generate-icons.py

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
ls -lh "$FDROID_APK"

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

echo "==> [3/8] Building standalone Stock Android 4.2 Home Launcher v49 (Long-Press Widgets) & Aurora Store (com.aurora.store)..."
rm -rf build/launcher && mkdir -p build/launcher/{res_compiled,gen,classes,dex}
"$AAPT2" compile --dir launcher-android4/res -o build/launcher/res_compiled/res.zip
"$AAPT2" link \
    -I "$ANDROID_JAR" \
    --manifest launcher-android4/AndroidManifest.xml \
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
    launcher-android4/src/ca/arc7freedom/launcher/*.java

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
"$AAPT2" compile --dir aurora-store/res -o build/aurora/res_compiled/res.zip
"$AAPT2" link \
    -I "$ANDROID_JAR" \
    --manifest aurora-store/AndroidManifest.xml \
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
    aurora-store/src/com/aurora/store/*.java

(cd build/aurora/dex && zip -qj ../unsigned.apk classes.dex)
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

mkdir -p app/src/main/assets/starter-apps
cp -f app/src/main/res/drawable-mdpi/ic_vlc.png vlc-player/res/drawable-mdpi/ic_vlc.png
cp -f app/src/main/res/drawable-hdpi/ic_vlc.png vlc-player/res/drawable-hdpi/ic_vlc.png
cp -f app/src/main/res/drawable-xhdpi/ic_vlc.png vlc-player/res/drawable-xhdpi/ic_vlc.png
cp -f app/src/main/res/drawable-xxhdpi/ic_vlc.png vlc-player/res/drawable-xxhdpi/ic_vlc.png
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
    "$AAPT2" compile --dir vlc-player/res -o build/vlc/res_compiled/res.zip
    "$AAPT2" link \
        -I "$ANDROID_JAR" \
        --manifest vlc-player/AndroidManifest.xml \
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
        vlc-player/src/org/videolan/vlc/*.java
    (cd build/vlc/dex && zip -qj ../unsigned.apk classes.dex)
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
cp -f releases/Start-Chromebook-Wired-Internet.sh app/src/main/assets/chromebook/Start-Chromebook-Wired-Internet.sh
cp -f releases/Start-Kobo-Second-Screen.py app/src/main/assets/chromebook/Start-Kobo-Second-Screen.py
cp -f releases/Start-PC-Stream-Deck.py app/src/main/assets/chromebook/Start-PC-Stream-Deck.py
cp -f releases/Start-PC-Stream-Deck.bat app/src/main/assets/chromebook/Start-PC-Stream-Deck.bat
cp -f releases/Start-PC-Stream-Deck.sh app/src/main/assets/chromebook/Start-PC-Stream-Deck.sh
cp -f releases/Start-PC-School-VPN-Relay.py app/src/main/assets/chromebook/Start-PC-School-VPN-Relay.py
cp -f releases/VanillaAndroid4Home.apk app/src/main/assets/VanillaAndroid4Home.apk

echo "==> [4/8] Compiling & packaging KoboArc7Freedom.apk v2.4.0 (Fixed F-Droid v1303 SHA-256 Verifier + School Wi-Fi Rescue + System-Wide VPN & Official Tailscale + PC Stream Deck + Drag-Drop Launcher v54 + VK 4-Store E-Reader + VLC)..."
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
    app/src/main/java/ca/arc7freedom/app/*.java

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

echo "==> [6/8] Building 1-Tap Signature-Conflict-Free Root Upgrader (releases/Upgrade-My-Kobo-To-v2.4.0.apk)..."
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
    upgrader/src/main/java/ca/arc7freedom/upgrader/*.java

(cd build/upgrader/dex && zip -qj ../unsigned.apk classes.dex)
"$ZIPALIGN" -f -p 4 build/upgrader/unsigned.apk build/upgrader/aligned.apk
rm -f releases/Upgrade-My-Kobo-To-v1.*.apk releases/Upgrade-My-Kobo-To-v2.0.*.apk releases/Upgrade-My-Kobo-To-v2.1.*.apk releases/Upgrade-My-Kobo-To-v2.2.*.apk releases/Upgrade-My-Kobo-To-v2.3.*.apk releases/Upgrade-My-Kobo-To-v2.4.0.apk
"$JAVA_BIN" -jar "$APKSIGNER_JAR" sign \
    --ks "$KEYSTORE_PATH" \
    --ks-pass "pass:$KEYSTORE_PASS" \
    --key-pass "pass:$KEYSTORE_PASS" \
    --ks-key-alias "$KEYSTORE_ALIAS" \
    --min-sdk-version 15 \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out releases/Upgrade-My-Kobo-To-v2.4.0.apk \
    build/upgrader/aligned.apk
cp -f app/src/main/assets/starter-apps/FDroidClassicLegacy.apk releases/FDroidClassicLegacy.apk

echo "==> [7/8] Generating local Wi-Fi portal & writing SHA256SUMS.txt..."
rm -rf docs
rm -f releases/*.idsig app/src/main/assets/starter-apps/*.idsig
chmod +x releases/Backup-Kobo-To-Laptop-ADB.sh releases/Start-Chromebook-Wired-Internet.sh releases/Start-Kobo-Second-Screen.py releases/Start-PC-Stream-Deck.py releases/Start-PC-Stream-Deck.sh releases/Start-PC-School-VPN-Relay.py tools/serve-over-wifi.sh
"$PY_BIN" tools/serve-over-wifi.py --write-static
(cd releases && sha256sum \
    Upgrade-My-Kobo-To-v2.4.0.apk \
    KoboArc7Freedom.apk \
    VLC.apk \
    AuroraStore.apk \
    FDroidClassicLegacy.apk \
    VanillaAndroid4Home.apk \
    Start-PC-Stream-Deck.py \
    Start-PC-Stream-Deck.bat \
    Start-PC-Stream-Deck.sh \
    Start-PC-School-VPN-Relay.py \
    Start-Chromebook-Wired-Internet.sh \
    Start-Kobo-Second-Screen.py \
    Backup-Kobo-To-Laptop-ADB.bat \
    Backup-Kobo-To-Laptop-ADB.sh \
    Start-Kobo-WiFi-Server.bat \
    Start-Kobo-WiFi-Server.sh > SHA256SUMS.txt)
cat releases/SHA256SUMS.txt
ls -lh \
    releases/Upgrade-My-Kobo-To-v2.4.0.apk \
    releases/KoboArc7Freedom.apk \
    releases/VLC.apk \
    releases/AuroraStore.apk \
    releases/FDroidClassicLegacy.apk \
    releases/VanillaAndroid4Home.apk \
    releases/Start-PC-Stream-Deck.py \
    releases/Start-PC-Stream-Deck.bat \
    releases/Start-PC-Stream-Deck.sh \
    releases/Start-PC-School-VPN-Relay.py \
    releases/Start-Chromebook-Wired-Internet.sh \
    releases/Start-Kobo-Second-Screen.py \
    releases/Backup-Kobo-To-Laptop-ADB.bat \
    releases/Backup-Kobo-To-Laptop-ADB.sh \
    releases/Start-Kobo-WiFi-Server.bat \
    releases/Start-Kobo-WiFi-Server.sh \
    releases/index.html
