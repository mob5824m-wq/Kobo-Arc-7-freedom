#!/usr/bin/env python3
"""Production Java/JS QR bytes -> real QR encode/decode -> reassembly/integrity tests.

Camera pixels are synthetic grayscale frames, not a physical camera or device test.
No HTTP server, CDN, platform scanner or network is used.
"""
from pathlib import Path
import os
import random
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'app/src/main/java'
NS = '{http://schemas.android.com/apk/res/android}'

def run(command):
    subprocess.run(list(map(str, command)), cwd=ROOT, check=True, timeout=120)

def main():
    default_tools = Path('/opt/toolchain') if Path('/opt/toolchain/jdk/bin/java').is_file() else ROOT / 'build/toolchain'
    toolchain = Path(os.environ.get('TOOLCHAIN_DIR') or default_tools)
    java = os.environ.get('JAVA') or (str(toolchain / 'jdk/bin/java') if (toolchain / 'jdk/bin/java').is_file() else shutil.which('java'))
    javac = os.environ.get('JAVAC') or (str(toolchain / 'jdk/bin/javac') if (toolchain / 'jdk/bin/javac').is_file() else shutil.which('javac'))
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or toolchain / 'sdk')
    bundled_jar = toolchain / 'sdk/platforms/android-17/android.jar'
    jar = Path(os.environ.get('ANDROID_JAR') or (bundled_jar if bundled_jar.is_file() else sdk / 'platforms/android-17/android.jar'))
    if not java or not javac or not jar.is_file():
        raise SystemExit('Run scripts/bootstrap-toolchain.sh or install a JDK and Android SDK platforms;android-17')
    node = shutil.which('node')
    if not node:
        raise SystemExit('Node.js 18+ is required for the offline browser/Android QR interoperability tests')
    run(['python3', 'scripts/build-qr-companion.py', '--check'])
    run([node, 'tests/js/qr-browser-ui-harness.cjs'])
    with tempfile.TemporaryDirectory(prefix='arc7-optical-') as temporary:
        temp = Path(temporary)
        (temp/'payload.bin').write_bytes(random.Random(713).randbytes(8193))
        run([node, 'tests/js/qr-transfer-harness.cjs', temp, 'prepare'])
        classes = temp/'classes'; classes.mkdir()
        sources = sorted((SRC/'com/google/zxing').rglob('*.java'))
        sources += [SRC/'ca/arc7freedom/app'/name for name in ('Arc7QrTransfer.java', 'QrCodeEncoder.java', 'Arc7QrCameraDecoder.java')]
        run([javac, '-nowarn', '-cp', jar, '-d', classes, *sources, ROOT/'tests/java/QrTransferHarness.java'])
        run([java, '-cp', os.pathsep.join(map(str,(classes,jar))), 'ca.arc7freedom.app.QrTransferHarness', temp])
        run([node, 'tests/js/qr-transfer-harness.cjs', temp, 'receive-java'])
    # Real registration, permissions and lifecycle hooks, including the standalone Files APK.
    manifest = ET.parse(ROOT/'split-apps/filemanager/AndroidManifest.xml').getroot()
    assert any(p.get(NS+'name') == 'android.permission.CAMERA' for p in manifest.findall('uses-permission'))
    component = next(a for a in manifest.findall('application/activity') if a.get(NS+'name').endswith('.Arc7QrTransferActivity'))
    assert component.get(NS+'exported') == 'false' and component.get(NS+'screenOrientation') == 'portrait'
    standalone = ET.parse(ROOT/'split-apps/qrtransfer/AndroidManifest.xml').getroot()
    assert standalone.get('package') == 'ca.arc7freedom.qrtransfer'
    permissions = {p.get(NS+'name') for p in standalone.findall('uses-permission')}
    assert 'android.permission.CAMERA' in permissions and 'android.permission.INTERNET' not in permissions
    assert standalone.find('application').get(NS+'allowBackup') == 'false'
    home = next(a for a in standalone.findall('application/activity') if a.get(NS+'name').endswith('Arc7QrTransferHomeActivity'))
    assert home.get(NS+'exported') == 'true'
    assert any(a.get(NS+'name') == 'android.intent.action.SEND' for a in home.findall('intent-filter/action'))
    native_home = (SRC/'ca/arc7freedom/app/Arc7QrTransferHomeActivity.java').read_text()
    for hook in ('ACTION_GET_CONTENT', 'directory.listFiles()', 'Uri.fromFile(file)', 'OpenableColumns.DISPLAY_NAME', 'qr_density', 'qr_legacy', 'qr_resume', 'discardPending'):
        assert hook in native_home, hook
    files = (SRC/'ca/arc7freedom/app/Arc7FileManagerActivity.java').read_text()
    assert 'Send file bytes via QR (offline)' in files and 'Receive QR bytes' in files
    assert 'Arc7QrFileShareService.startShare' in files, 'Preserve separate LAN sharing'
    camera = (SRC/'ca/arc7freedom/app/Arc7QrTransferActivity.java').read_text()
    for hook in ('Camera.open(cameraId)', 'CAMERA_FACING_BACK', 'CAMERA_FACING_FRONT', 'setPreviewCallbackWithBuffer',
                 'decoder.decode(bytes, width, height)', 'sink.verify()', 'sink.save(destination)', 'newSingleThreadExecutor',
                 'surfaceDestroyed', 'onPause()', 'worker.shutdownNow()', 'sender.close()', 'receiver.close()',
                 'Go to frame', 'pendingData', '700'):
        assert hook in camera, hook
    assert 'http://' not in camera and 'https://' not in camera and 'BarcodeDetector' not in camera
    for hook in ('receiveStore(), true', 'receiver.checkpoint()', 'qr_resume', 'openInputStream', 'getFD'):
        assert hook in camera or hook in (SRC/'ca/arc7freedom/app/Arc7QrTransfer.java').read_text(), hook
    browser = (ROOT/'tools/qr-transfer/browser.js').read_text()
    assert 'fetch(' not in browser and 'XMLHttpRequest' not in browser and 'new WebSocket' not in browser
    html = (ROOT/'tools/qr-transfer.html').read_text()
    assert "connect-src 'none'" in html and 'src="http' not in html and 'src="//' not in html
    print('ALL OPTICAL TRANSFER REGRESSIONS PASSED (synthetic camera + host JVM/browser code; not physical camera validation)')

if __name__ == '__main__':
    main()
