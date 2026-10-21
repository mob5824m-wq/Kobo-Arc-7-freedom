#!/usr/bin/env bash
# ==============================================================================
# Kobo Arc 7 Freedom — Reproducible Full Native + Dalvik (DEX 035) Build Script
#
# Builds BOTH signed Android 4.2.2 (API 17 / minSdk 15) APKs from pure source:
#   1. Pre-packages all 120 modern 2026 Mozilla Root CA certificates offline
#      into `app/src/main/assets/cacerts/` (`<subject_hash_old>.<idx>` + bundle)
#   2. Cross-compiles all 4 static ARMv7 EABI5 native binaries:
#        - `libarc7su.so`   (Setuid + daemon fallback `su`)
#        - `libarc7root.so` (Multi-stage Linux 3.4 ARMv7 kernel root engine)
#        - `libarc7tool.so` (Streaming zlib + FIPS-180-4 SHA-256 partition tool)
#        - `libarc7tls.so`  (Mbed-TLS 3.6 LTS full TLS 1.3 + TLS 1.2 engine,
#                            UDP NTP clock sync & local Browser Gateway)
#   3. Builds & v1/v2/v3 signs `releases/VanillaAndroid4Home.apk` &
#      `releases/KoboArc7Freedom.apk` + `releases/SHA256SUMS.txt`
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
if [ -x "/opt/toolchain/sdk/build-tools/34.0.0/d8" ]; then
    D8_BIN="/opt/toolchain/sdk/build-tools/34.0.0/d8"
elif [ -n "$LATEST_BUILD_TOOLS" ] && [ -x "$LATEST_BUILD_TOOLS/d8" ]; then
    D8_BIN="$LATEST_BUILD_TOOLS/d8"
fi

ECJ="${ECJ:-/opt/toolchain/jars/ecj-4.8.jar}"
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
    else
        "$JAVA_BIN" -cp "build:$DFODA" RunDexer \
            --dex "--saida=$out_dex_dir/classes.dex" \
            "$out_classes/../classes.jar"
    fi
}

echo "==> [1/7] Pre-packaging offline 2026 CAs, starter Android 4.2 App Store APK & launcher icons..."
"$PY_BIN" scripts/generate-cacerts.py
"$PY_BIN" scripts/generate-icons.py

mkdir -p app/src/main/assets/starter-apps
FDROID_APK="app/src/main/assets/starter-apps/FDroidClassicLegacy.apk"
FDROID_URL="https://github.com/Smu1zel/fdroidclassiclegacy/releases/download/v1.3-beta2-PATCH/app-debug.apk"
if [ ! -s "$FDROID_APK" ]; then
    echo "    Downloading pre-bundled offline F-Droid Classic Legacy (Android 4.1+ / 4.2.2 + Conscrypt TLS 1.3)..."
    curl -fsSL "$FDROID_URL" -o "$FDROID_APK"
fi
ls -lh "$FDROID_APK"

echo "==> [2/7] Cross-compiling static ARMv7 EABI5 native binaries (su, arc7root, arc7tool, arc7tls)..."
if [ ! -d "$ZLIB_DIR" ]; then
    git clone --depth 1 https://github.com/madler/zlib.git "$ZLIB_DIR"
fi
if [ ! -d "$MBEDTLS_DIR" ]; then
    git clone --depth 1 --branch v3.6.2 https://github.com/Mbed-TLS/mbedtls.git "$MBEDTLS_DIR"
fi

mkdir -p build/native/armeabi-v7a build/native/armeabi build/mbedtls-arm build/keystore releases
CFLAGS_ARM="-target arm-linux-musleabi -march=armv7-a -mcpu=cortex_a8 -Os -s -static -Wall -Wextra -Wno-unused-parameter"

if [ ! -f build/mbedtls-arm/libmbedtls_all.a ]; then
    "$PY_BIN" - "$MBEDTLS_DIR" << 'PYEOF'
import concurrent.futures, glob, os, shutil, subprocess, sys
mbed = sys.argv[1]
zig = [sys.executable, "-m", "ziglang"]
try:
    import ziglang  # noqa
except ImportError:
    zig = ["zig"]
srcs = sorted(glob.glob(os.path.join(mbed, "library/*.c")))
def comp(s):
    o = os.path.join("build/mbedtls-arm", os.path.basename(s)[:-2] + ".o")
    subprocess.run(
        zig + ["cc", "-target", "arm-linux-musleabi", "-march=armv7-a", "-mcpu=cortex_a8",
               "-Os", "-ffunction-sections", "-fdata-sections",
               f"-I{mbed}/include", f"-I{mbed}/library", "-c", s, "-o", o],
        check=True,
    )
    return o
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
    objs = list(ex.map(comp, srcs))
subprocess.run(zig + ["ar", "rcs", "build/mbedtls-arm/libmbedtls_all.a", *objs], check=True)
PYEOF
fi

$ZIG_CMD cc $CFLAGS_ARM native/su.c -o build/native/armeabi-v7a/libarc7su.so
$ZIG_CMD cc $CFLAGS_ARM native/arc7root.c -o build/native/armeabi-v7a/libarc7root.so -lpthread
$ZIG_CMD cc $CFLAGS_ARM -DHAVE_UNISTD_H=1 -DHAVE_STDARG_H=1 -I"$ZLIB_DIR" native/arc7tool.c \
    "$ZLIB_DIR"/adler32.c "$ZLIB_DIR"/compress.c "$ZLIB_DIR"/crc32.c \
    "$ZLIB_DIR"/deflate.c "$ZLIB_DIR"/gzclose.c "$ZLIB_DIR"/gzlib.c \
    "$ZLIB_DIR"/gzread.c "$ZLIB_DIR"/gzwrite.c "$ZLIB_DIR"/infback.c \
    "$ZLIB_DIR"/inffast.c "$ZLIB_DIR"/inflate.c "$ZLIB_DIR"/inftrees.c \
    "$ZLIB_DIR"/trees.c "$ZLIB_DIR"/uncompr.c "$ZLIB_DIR"/zutil.c \
    -o build/native/armeabi-v7a/libarc7tool.so
$ZIG_CMD cc $CFLAGS_ARM -ffunction-sections -fdata-sections -Wl,--gc-sections \
    -I"$MBEDTLS_DIR/include" native/arc7tls.c build/mbedtls-arm/libmbedtls_all.a -lpthread \
    -o build/native/armeabi-v7a/libarc7tls.so

cp build/native/armeabi-v7a/*.so build/native/armeabi/

if [ ! -f build/keystore/arc7freedom.jks ]; then
    "$KEYTOOL_BIN" -genkeypair -noprompt \
        -keystore build/keystore/arc7freedom.jks \
        -storepass arc7freedom \
        -keypass arc7freedom \
        -alias arc7key \
        -keyalg RSA -keysize 2048 -sigalg SHA256withRSA \
        -validity 10000 \
        -dname "CN=Kobo Arc 7 Freedom, OU=Open Source, O=Arc7Freedom, L=Kitchener, ST=ON, C=CA"
fi

if [ -z "$D8_BIN" ] && [ -f "$DFODA" ]; then
    cat > build/RunDexer.java << 'JEOF'
public class RunDexer {
    public static void main(String[] args) throws Exception {
        com.dfoda.dexer.Main.rodar(args);
    }
}
JEOF
    if [ -f "$ECJ" ]; then
        "$JAVA_BIN" -jar "$ECJ" -1.8 -cp "$DFODA" -d build/ build/RunDexer.java
    else
        "$JAVAC_BIN" -source 1.8 -target 1.8 -Xlint:-options -cp "$DFODA" -d build/ build/RunDexer.java
    fi
fi

echo "==> [3/7] Building standalone Stock Android 4.2 Home Launcher (VanillaAndroid4Home.apk)..."
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
    --ks build/keystore/arc7freedom.jks \
    --ks-pass pass:arc7freedom \
    --key-pass pass:arc7freedom \
    --ks-key-alias arc7key \
    --min-sdk-version 15 \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out releases/VanillaAndroid4Home.apk \
    build/launcher/aligned.apk

mkdir -p app/src/main/assets
cp -f releases/VanillaAndroid4Home.apk app/src/main/assets/VanillaAndroid4Home.apk

echo "==> [4/7] Compiling & packaging KoboArc7Freedom.apk (API 17 + 120 2026 CAs + TLS 1.3 + ARMv7 binaries)..."
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

echo "==> [5/7] Aligning & signing KoboArc7Freedom.apk with v1 (JAR Android 4.2) + v2 + v3 schemes..."
"$ZIPALIGN" -f -p 4 build/app/unsigned.apk build/app/aligned.apk
"$JAVA_BIN" -jar "$APKSIGNER_JAR" sign \
    --ks build/keystore/arc7freedom.jks \
    --ks-pass pass:arc7freedom \
    --key-pass pass:arc7freedom \
    --ks-key-alias arc7key \
    --min-sdk-version 15 \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out releases/KoboArc7Freedom.apk \
    build/app/aligned.apk

echo "==> [6/7] Generating static Web Portals & writing SHA256SUMS.txt..."
rm -f releases/*.idsig
"$PY_BIN" tools/serve-over-wifi.py --write-static
(cd releases && sha256sum KoboArc7Freedom.apk VanillaAndroid4Home.apk Start-Kobo-WiFi-Server.bat Start-Kobo-WiFi-Server.sh > SHA256SUMS.txt)
cat releases/SHA256SUMS.txt
ls -lh releases/KoboArc7Freedom.apk releases/VanillaAndroid4Home.apk releases/Start-Kobo-WiFi-Server.bat releases/Start-Kobo-WiFi-Server.sh docs/index.html
