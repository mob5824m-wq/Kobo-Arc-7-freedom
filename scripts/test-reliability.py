#!/usr/bin/env python3
"""Offline reliability gates: production logging/cache/profile + real resumable HTTP/filesystem.

Android speaker/focus/camera/storage durability and real trusted-LAN behaviour still require
Kobo testing. Browser tests use fakes; host FFmpeg is not Android's decoder.
"""
from pathlib import Path
from array import array
import argparse
import hashlib
import importlib.util
import math
import os
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT/'app/src/main/java/ca/arc7freedom/app'
SUPPORT = ROOT/'shared/java/ca/arc7freedom/support'
ANDROID = '{http://schemas.android.com/apk/res/android}'


def integration():
    manifests = [ROOT/'app/src/main/AndroidManifest.xml', *sorted((ROOT/'split-apps').glob('*/AndroidManifest.xml'))]
    for name in ('launcher-android4', 'aurora-store', 'upgrader', 'vlc-player'):
        manifests.append(ROOT/name/'src/main/AndroidManifest.xml')
    for manifest in manifests:
        document = ET.parse(manifest).getroot()
        app = document.find('application')
        app_name = app.get(ANDROID+'name', '') if app is not None else ''
        if app_name.startswith('.'): app_name = document.get('package') + app_name
        assert app is not None
        assert app_name in ('ca.arc7freedom.app.Arc7Application', 'ca.arc7freedom.support.SupportApplication'), manifest
        tools = [a for a in app.findall('activity') if a.get(ANDROID+'name') == 'ca.arc7freedom.support.SupportActivity']
        assert len(tools) == 1 and tools[0].get(ANDROID+'exported') == 'true', manifest
        services = [n.get(ANDROID+'name', '') for n in app.findall('service')]
        if any(n.endswith('Arc7AudioService') for n in services):
            assert any(a.get(ANDROID+'name', '').endswith('Arc7PlaybackDiagnosticsActivity') for a in app.findall('activity')), manifest
            assert any(a.get(ANDROID+'name', '').endswith('Arc7MediaButtonReceiver') for a in app.findall('receiver')), manifest
    app = (SUPPORT/'AppSupport.java').read_text()
    assert 'Context.MODE_PRIVATE' in app and 'registerActivityLifecycleCallbacks' in app and 'installCrashHandler()' in app
    screen = (SUPPORT/'SupportActivity.java').read_text()
    assert 'getIntent(' not in screen and 'MediaCache.trim(0)' in screen and 'savedMediaUsage' in screen
    assert 'MODE_WORLD' not in screen and 'logcat' not in (SUPPORT/'DebugLog.java').read_text()
    for name in ('SupportActivity.java',):
        assert 'SupportUi.panel' in (SUPPORT/name).read_text()
    diagnostic = (SRC/'Arc7PlaybackDiagnosticsActivity.java').read_text()
    for name in ('pcm.wav', 'mp3.mp3', 'aac-lc.m4a'):
        assert name in diagnostic
    share = (SRC/'Arc7QrFileShareService.java').read_text()
    assert 'new File(getFilesDir(),"lan-uploads")' in share and 'SESSION_MILLIS' in share
    assert 'synchronized(LIFECYCLE)' in share and 'running || starting' in share
    assert 'discardPendingIfStopped(store)' in (SRC/'Arc7FileManagerActivity.java').read_text()
    for module in ('app', 'launcher-android4', 'aurora-store', 'upgrader', 'vlc-player'):
        assert '${rootProject.projectDir}/shared/java' in (ROOT/module/'build.gradle').read_text(), module
    upgrader_gradle=(ROOT/'upgrader/build.gradle').read_text()
    assert 'java.srcDir "${rootProject.projectDir}/app/src/main/java"' in upgrader_gradle
    assert '"ca/arc7freedom/app/BundledApk.java"' in upgrader_gradle and 'java.include' in upgrader_gradle
    build = (ROOT/'scripts/build-all.sh').read_text()
    assert 'shared/java/ca/arc7freedom/support/*.java' in build
    assert build.count('cp -R app/src/main/assets/audio-self-test') == 3
    assert 'cp -R app/src/main/assets/audio-self-test "$empty_assets/"' in build
    assert build.count('cp -R app/src/main/assets/lan-share') == 2
    optical = (SRC/'Arc7QrTransfer.java').read_text()
    assert '67108864' in optical or '64L * 1024L * 1024L' in optical or '64 * 1024 * 1024' in optical
    # Exercise the actual binary manifest transformation before the full signed build, too.
    spec = importlib.util.spec_from_file_location('fdroid_support', ROOT/'scripts/patch-fdroid-apk.py')
    patch = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(patch)
    from androguard.core.axml import AXMLPrinter
    from loguru import logger
    logger.disable('androguard')
    with zipfile.ZipFile(ROOT/'releases/FDroidClassicLegacy.apk') as archive:
        raw = archive.read('AndroidManifest.xml')
    injected = patch.patch_manifest_support(raw)
    assert patch.patch_manifest_support(injected) == injected
    application = AXMLPrinter(injected).get_xml_obj().find('application')
    assert application.get(ANDROID+'name') == 'org.fdroid.fdroid.FDroidApp'
    providers = [n for n in application.findall('provider') if n.get(ANDROID+'name') == 'ca.arc7freedom.support.SupportProvider']
    assert len(providers) == 1 and providers[0].get(ANDROID+'exported') == 'false'
    with tempfile.TemporaryDirectory(prefix='arc7-fdroid-log-source-') as tmp:
        _, implementations = patch.build_fdroid_patch_sources(tmp, str(ROOT))
        bridge = next(Path(p).read_text() for p in implementations if p.endswith('/Arc7Bridge.java'))
        method = bridge.split('private static void writeCrashLog(', 1)[1].split('\n    }', 1)[0]
        assert 'DebugLog.error' in method and 'printStackTrace' not in method and ' Log.e(' not in method
        assert any(p.endswith('/AppSupport.java') for p in implementations)
        for path in implementations:
            generated = Path(path).read_text()
            assert 'printStackTrace(' not in generated and 'Log.e(' not in generated and 'Log.w(' not in generated, path
        caught = bridge.split('public static void logCaught(', 1)[1].split('\n    }', 1)[0]
        assert 'DebugLog.error("fdroid.operation_failed", error)' in caught and 'FileOutputStream' not in caught
    print(f'PASS integration: {len(manifests)} owned source manifests expose per-UID App tools; audio owners have diagnostics/buttons; bounded private F-Droid bootstrap preserves FDroidApp and is binary-XML idempotent; offline assets and independent optical QR retained')


def audio_assets(ffmpeg):
    folder = ROOT/'app/src/main/assets/audio-self-test'
    hashes = dict(line.split('  ', 1)[::-1] for line in (folder/'SHA256SUMS.txt').read_text().splitlines())
    assert set(hashes) == {'pcm.wav', 'mp3.mp3', 'aac-lc.m4a'}
    source = (SRC/'Arc7PlaybackDiagnosticsActivity.java').read_text()
    for name, digest in hashes.items():
        assert hashlib.sha256((folder/name).read_bytes()).hexdigest() == digest and digest in source
        assert (folder/name).stat().st_size < 100000
        if ffmpeg:
            raw = subprocess.check_output([ffmpeg, '-nostdin', '-hide_banner', '-loglevel', 'error', '-xerror', '-i', str(folder/name), '-map', '0:a:0', '-ac', '1', '-ar', '16000', '-f', 's16le', '-'], timeout=15)
            pcm = array('h', raw)
            import sys
            if sys.byteorder != 'little': pcm.byteswap()
            assert 23000 <= len(pcm) <= 27000, (name, len(pcm))
            rms = math.sqrt(sum(int(n)**2 for n in pcm)/len(pcm))
            assert 200 < rms < 4000 and max(abs(n) for n in pcm) < 6000, (name, rms)
    print('PASS offline test assets: pinned WAV/MP3/AAC-LC hashes, <100 KiB each' + (' and quiet non-silent ~1.5 s host decoding (not Kobo audio proof)' if ffmpeg else ' (host decoder unavailable)'))


def verify_apks():
    from androguard.core.apk import APK
    from androguard.core.dex import DEX
    from loguru import logger
    logger.disable('androguard')
    dex_classes = {}
    count = 0
    for path in [*sorted((ROOT/'releases').glob('*.apk')), *sorted((ROOT/'releases/apps').glob('*.apk'))]:
        apk = APK(str(path))
        application = apk.get_android_manifest_xml().find('application')
        with zipfile.ZipFile(path) as archive:
            names = set()
            for name in archive.namelist():
                if '/' not in name and name.startswith('classes') and name.endswith('.dex'):
                    raw = archive.read(name)
                    key = hashlib.sha256(raw).hexdigest()
                    if key not in dex_classes: dex_classes[key] = {c.get_name() for c in DEX(raw).get_classes()}
                    names.update(dex_classes[key])
            if apk.get_package() == 'org.videolan.vlc' and 'Lca/arc7freedom/support/AppSupport;' not in names:
                assert 'org.videolan.vlc.gui.DebugLogActivity' in apk.get_activities()
                assert 'Lorg/videolan/vlc/DebugLogService;' in names
                print('PASS official VLC: existing native debug recorder/UI retained without injecting code or changing signing identity (native logs are not sanitized by Arc7)')
                continue
            assert {'Lca/arc7freedom/support/DebugLog;', 'Lca/arc7freedom/support/AppSupport;', 'Lca/arc7freedom/support/SupportActivity;'} <= names, path
            assert 'ca.arc7freedom.support.SupportActivity' in apk.get_activities(), path
            if apk.get_package() == 'eu.bubu1.fdroidclassic.debug':
                assert application.get(ANDROID+'name') == 'org.fdroid.fdroid.FDroidApp'
                assert 'ca.arc7freedom.support.SupportProvider' in apk.get_providers()
                provider=next(p for p in application.findall('provider') if p.get(ANDROID+'name')=='ca.arc7freedom.support.SupportProvider')
                assert provider.get(ANDROID+'exported')=='false'
                bridge=next(c for c in DEX(archive.read('classes.dex')).get_classes() if c.get_name()=='Lorg/fdroid/fdroid/Arc7Bridge;')
                caught=next(m for m in bridge.get_methods() if m.get_name()=='logCaught')
                ops='\n'.join(i.get_output() for i in caught.get_code().get_bc().get_instructions())
                assert 'Lca/arc7freedom/support/DebugLog;->error' in ops, ops
                assert all(forbidden not in ops for forbidden in ('printStackTrace', 'Ljava/io/FileOutputStream;', 'Landroid/util/Log;')), ops
            else:
                name = application.get(ANDROID+'name', '')
                if name.startswith('.'): name = apk.get_package() + name
                assert name in ('ca.arc7freedom.app.Arc7Application', 'ca.arc7freedom.support.SupportApplication'), path
            if 'ca.arc7freedom.app.Arc7AudioService' in apk.get_services():
                assert 'ca.arc7freedom.app.Arc7PlaybackDiagnosticsActivity' in apk.get_activities(), path
                for name in ('pcm.wav', 'mp3.mp3', 'aac-lc.m4a'):
                    member='assets/audio-self-test/'+name
                    assert member in archive.namelist(), f'{path}: audio owner is missing offline asset {member}'
                    assert archive.read(member) == (ROOT/'app/src/main/assets/audio-self-test'/name).read_bytes(), path
            if 'ca.arc7freedom.app.Arc7QrFileShareService' in apk.get_services():
                for name in ('sha256.js', 'upload.js'):
                    assert archive.read('assets/lan-share/'+name) == (ROOT/'app/src/main/assets/lan-share'/name).read_bytes(), path
        count += 1
    assert count >= 39, f'Incomplete owned APK set: {count}'
    print(f'PASS shipped APK coverage: {count} owned APKs contain initialized private log/UI classes, F-Droid bootstrap and correct offline diagnostics/LAN assets; official VLC kept separate')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apks', action='store_true')
    args = parser.parse_args()
    integration()
    tools = Path(os.environ.get('TOOLCHAIN_DIR', ROOT/'build/toolchain'))
    java = os.environ.get('JAVA') or (str(tools/'jdk/bin/java') if (tools/'jdk/bin/java').is_file() else shutil.which('java'))
    javac = os.environ.get('JAVAC') or (str(tools/'jdk/bin/javac') if (tools/'jdk/bin/javac').is_file() else shutil.which('javac'))
    assert java and javac, 'Bootstrap the Android test toolchain / JDK first'
    with tempfile.TemporaryDirectory(prefix='arc7-reliability-') as tmp:
        tmp = Path(tmp)
        sources = [SUPPORT/name for name in ('DebugLog.java', 'RuntimePolicy.java', 'MediaCache.java', 'StorageUsage.java')]
        sources += [SRC/name for name in ('Arc7QrFileShareServer.java', 'Arc7ResumableUploads.java', 'LegacyAacConfig.java', 'MorpheRemuxer.java', 'MorpheStreamServer.java')]
        sources += list((ROOT/'tests/support/org/json').glob('*.java'))
        sources += [ROOT/'tests/java/ReliabilityHarness.java']
        subprocess.run([javac, '-nowarn', '-d', str(tmp/'classes'), *map(str, sources)], check=True)
        spec = importlib.util.spec_from_file_location('remux_fixtures', ROOT/'scripts/test-morphe-remux.py')
        fixture = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fixture)
        (tmp/'audio-init.bin').write_bytes(fixture.init_segment(3, 44100, 'mp4a', 0, 0))
        (tmp/'audio-fragment.bin').write_bytes(fixture.fragment(1, 3, 0, [(1024, 2048)]*50))
        subprocess.run([java, '-ea', '-cp', str(tmp/'classes'), 'ca.arc7freedom.app.ReliabilityHarness', str(tmp/'output'), str(ROOT/'app/src/main/assets/lan-share'), str(tmp)], check=True, timeout=90)
        node = shutil.which('node')
        assert node, 'Node is required for the offline LAN client regression'
        subprocess.run([node, str(ROOT/'tests/test-lan-client.js'), str(tmp/'output/reliability/legacy-client.js')], check=True, timeout=120)
    ffmpeg = os.environ.get('FFMPEG') or shutil.which('ffmpeg')
    if not ffmpeg:
        try:
            import imageio_ffmpeg
            ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
        except ImportError:
            pass
    audio_assets(ffmpeg)
    if args.apks: verify_apks()
    print('ALL OFFLINE RELIABILITY REGRESSIONS PASSED (host tests/fakes; no physical Kobo, LAN, camera, sound-output or live-provider claim)')


if __name__ == '__main__':
    main()
