#!/usr/bin/env python3
"""Offline 2.6 regressions: real vault/TOTP/media/collection/pin algorithms and mocked PC input.

Uses host JVM Android fakes + synthetic HTTP, not a device, live provider or security audit.
No platform keyboard events are emitted: all Windows/Mac/Linux backends are mocked.
"""
from pathlib import Path
import importlib.util
import json
import os
import shutil
import shlex
import socket
import subprocess
import tempfile
import threading
import xml.etree.ElementTree as ET
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'app/src/main/java/ca/arc7freedom/app'
NS = '{http://schemas.android.com/apk/res/android}'

STUBS = {
    'android/app/Activity.java': '''package android.app;public class Activity extends android.content.Context { public boolean finishing;public boolean isFinishing(){return finishing;} }''',
    'android/content/DialogInterface.java': '''package android.content;public interface DialogInterface {int BUTTON_NEGATIVE=-2;interface OnCancelListener{void onCancel(DialogInterface d);}interface OnClickListener{void onClick(DialogInterface d,int which);} }''',
    'android/os/Handler.java': '''package android.os;import java.util.concurrent.*;public class Handler {private static final ConcurrentLinkedQueue<Runnable> queue=new ConcurrentLinkedQueue<Runnable>();public boolean post(Runnable r){queue.add(r);return true;}public static void drain(){Runnable r;while((r=queue.poll())!=null)r.run();} }''',
    'android/app/ProgressDialog.java': '''package android.app;import android.content.*;public class ProgressDialog implements DialogInterface {public static final int STYLE_HORIZONTAL=1;public static ProgressDialog latest;public boolean showing,indeterminate;public int progress,max;private OnCancelListener cancel;
 public ProgressDialog(Activity a){latest=this;}public void setTitle(String t){}public void setMessage(String t){}public void setProgressStyle(int s){}public void setMax(int n){max=n;}public void setIndeterminate(boolean b){indeterminate=b;}public void setCancelable(boolean b){}public void setProgress(int p){progress=p;}
 public void setOnCancelListener(OnCancelListener c){cancel=c;}public void setButton(int n,String text,OnClickListener c){}public void show(){showing=true;}public void dismiss(){showing=false;}public void cancel(){if(cancel!=null)cancel.onCancel(this);dismiss();} }''',
    'android/widget/Toast.java': '''package android.widget;import java.util.*;public class Toast {public static final int LENGTH_LONG=1;public static final List<String> messages=new ArrayList<String>();private String text;public static Toast makeText(android.content.Context c,String s,int d){Toast t=new Toast();t.text=s;return t;}public void show(){messages.add(text);} }''',
    'android/content/Context.java': '''package android.content; import java.util.*; import java.io.*;
public class Context { public static final int MODE_PRIVATE=0; public File cache; private Map<String,SharedPreferences> prefs=new HashMap<String,SharedPreferences>();
 public Context getApplicationContext(){return this;} public String getPackageName(){return "ca.arc7freedom.ytmusic";} public File getCacheDir(){return cache;}
 public SharedPreferences getSharedPreferences(String name,int m){if(!prefs.containsKey(name))prefs.put(name,new SharedPreferences());return prefs.get(name);} }''',
    'android/content/SharedPreferences.java': '''package android.content; import java.util.*;
public class SharedPreferences { private Map<String,Object> values=new HashMap<String,Object>();
 public String getString(String k,String d){Object v=values.get(k);return v instanceof String?(String)v:d;}
 public boolean getBoolean(String k,boolean d){Object v=values.get(k);return v instanceof Boolean?(Boolean)v:d;}
 public int getInt(String k,int d){Object v=values.get(k);return v instanceof Number?((Number)v).intValue():d;} public long getLong(String k,long d){Object v=values.get(k);return v instanceof Number?((Number)v).longValue():d;}
 public Editor edit(){return new Editor();} public class Editor { public Editor putString(String k,String v){values.put(k,v);return this;}
 public Editor putLong(String k,long v){values.put(k,v);return this;} public Editor putInt(String k,int v){values.put(k,v);return this;}public Editor putBoolean(String k,boolean v){values.put(k,v);return this;} public boolean commit(){return true;} } }''',
    'android/net/Uri.java': '''package android.net; import java.util.*; import java.net.*;
public class Uri { URI uri; private Uri(String value){uri=URI.create(value);} public static Uri parse(String v){return new Uri(v);}
 public String getHost(){return uri.getHost();} public List<String> getPathSegments(){List<String> parts=new ArrayList<String>();String path=uri.getPath();if(path!=null)for(String p:path.split("/"))if(!p.isEmpty())parts.add(p);return parts;}
 public String getQueryParameter(String key){try{String query=uri.getRawQuery();if(query==null)return null;for(String pair:query.split("&")){String[] p=pair.split("=",2);if(URLDecoder.decode(p[0],"UTF-8").equals(key))return p.length>1?URLDecoder.decode(p[1],"UTF-8"):"";}return null;}catch(Exception e){throw new IllegalArgumentException(e);}}
 public static String encode(String s){try{return URLEncoder.encode(s,"UTF-8").replace("+","%20");}catch(Exception e){throw new IllegalArgumentException(e);}} }''',
    'android/os/Environment.java': '''package android.os; import java.io.File; public class Environment { public static File root;public static File getExternalStorageDirectory(){return root;} }''',
    'android/util/Log.java': '''package android.util; public class Log { public static int i(String t,String m){return 0;} public static int e(String t,String m){return 0;} public static int w(String t,String m){return 0;} }''',
    'ca/arc7freedom/app/NowPlayingManager.java': 'package ca.arc7freedom.app; public class NowPlayingManager { public static String formatDurationMs(long v){return "fixture-duration";} }',
    'ca/arc7freedom/app/RootShell.java': 'package ca.arc7freedom.app; public class RootShell { public RootShell(android.content.Context c){} }',
    'ca/arc7freedom/app/NetworkCertManager.java': '''package ca.arc7freedom.app; public class NetworkCertManager { public static final int DEFAULT_PROXY_PORT=8118;public NetworkCertManager(android.content.Context c,RootShell r){} public boolean startBrowserGateway(){return true;} }''',
    'ca/arc7freedom/app/Arc7TlsGatewayService.java': '''package ca.arc7freedom.app; public class Arc7TlsGatewayService { public static boolean isGatewayAlive(){return true;}public static void ensureStarted(android.content.Context c){} }''',
}


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def check_host_keys():
    host_path = ROOT/'tools/Start-PC-Stream-Deck.py'
    assert host_path.read_bytes() == (ROOT/'app/src/main/assets/chromebook/Start-PC-Stream-Deck.py').read_bytes()
    host = load('streamdeck_features', host_path)
    for key in host.WIN_VK:
        if key not in (*host.MODIFIERS, 'CONTROL', 'CMD', 'SUPER'):
            assert len(host.parse_combo(key)) == 1
    assert host.parse_combo('shift-control-a') == ['CTRL', 'SHIFT', 'A']
    for invalid in ('', 'CTRL', 'F25', 'CTRL+CTRL+A', 'A+B', 'CTRL++A', '+A', 'A+'):
        try:
            host.parse_combo(invalid)
        except ValueError:
            pass
        else:
            raise AssertionError(f'Invalid PC key accepted: {invalid}')
    events = []
    with patch.object(host, 'IS_WIN', True), patch.object(host, 'IS_MAC', False), \
            patch.object(host, 'win32_key_event', lambda key, down: events.append((key, down))), \
            patch.object(host.time, 'sleep', lambda seconds: None):
        host.execute_hotkey('CTRL+SHIFT+A')
        assert events == [(17, True), (16, True), (65, True), (65, False), (16, False), (17, False)], events
        events.clear()
        one, two = host.HotkeySession(), host.HotkeySession()
        host.execute_hotkey('CTRL+A', 2, True, one)
        host.execute_hotkey('CTRL+A', 2, True, one)  # duplicate DOWN is idempotent
        host.execute_hotkey('CTRL+B', 2, True, two)
        host.execute_hotkey('CTRL+A', 2, False, one)
        assert (17, False) not in events and host._KEY_COUNTS == {'CTRL': 1, 'B': 1}, events
        host.execute_hotkey('CTRL+C')  # tap must not release another button's held Ctrl
        assert (17, False) not in events
        two.close()
        assert events[-2:] == [(66, False), (17, False)] and not host._KEY_COUNTS
        events.clear()
        def fail_key(key, down):
            events.append((key, down))
            if key == 65 and down:
                raise OSError('fixture key-down failure')
        with patch.object(host, 'win32_key_event', fail_key):
            try:
                host.execute_hotkey('CTRL+A')
            except OSError:
                pass
            else:
                raise AssertionError('Partial keyboard backend failure reported success')
        assert (17, False) in events and not host._KEY_COUNTS, events
        # The real framed TCP handler emits error acknowledgements and releases on EOF.
        events.clear()
        server, client = socket.socketpair()
        client.settimeout(3)
        with patch.object(host, 'sample_pc_telemetry', lambda: {'type': 'telemetry'}), patch.object(host, 'add_log', lambda message: None):
            thread = threading.Thread(target=host.handle_kobo_client, args=(server, ('fixture', 0)), daemon=True)
            thread.start()
            client.sendall((json.dumps({'type': 'deck_action', 'cmd': 'HOTKEY CTRL+F13', 'mode': 2, 'active': True})+'\n').encode())
            reader = client.makefile('r', encoding='utf-8')
            while json.loads(reader.readline()).get('type') != 'ack':
                pass
            assert (17, True) in events and (124, True) in events
            client.sendall((json.dumps({'type': 'deck_action', 'cmd': 'HOTKEY F25', 'mode': 0})+'\n').encode())
            result = json.loads(reader.readline())
            while result.get('type') != 'ack':
                result = json.loads(reader.readline())
            assert result['result'].startswith('ERROR:'), result
            reader.close(); client.close(); thread.join(3)
            assert not thread.is_alive() and not host._KEY_COUNTS
            assert events[-2:] == [(124, False), (17, False)], events
    calls = []
    def record(args, **kwargs):
        calls.append(args)
        assert kwargs.get('check') is True and kwargs.get('timeout') == 4
        return subprocess.CompletedProcess(args, 0)
    with patch.object(host, 'IS_WIN', False), patch.object(host, 'IS_MAC', True), patch.object(host.subprocess, 'run', record):
        host.execute_hotkey('CTRL+F13')
        assert 'key code 105 using {control down}' in calls[-1][-1]
        for key, mode in (('A', 2), ('F21', 0)):
            count = len(calls)
            try:
                host.execute_hotkey(key, mode)
            except ValueError:
                pass
            else:
                raise AssertionError('Unsupported Mac key/hold reported success')
            assert len(calls) == count
    calls.clear()
    with patch.object(host, 'IS_WIN', False), patch.object(host, 'IS_MAC', False), \
            patch.object(host.shutil, 'which', lambda name: '/fixture/xdotool'), \
            patch.object(host.subprocess, 'run', record), patch.dict(os.environ, {'XDG_SESSION_TYPE': 'x11', 'DISPLAY': ':99', 'WAYLAND_DISPLAY': ''}):
        host.execute_hotkey('CTRL+F13')
        assert calls == [['xdotool', 'keydown', 'ctrl'], ['xdotool', 'keydown', 'F13'], ['xdotool', 'keyup', 'F13'], ['xdotool', 'keyup', 'ctrl']], calls
        with patch.dict(os.environ, {'XDG_SESSION_TYPE': 'wayland'}):
            try:
                host.execute_hotkey('A')
            except RuntimeError:
                pass
            else:
                raise AssertionError('Wayland input reported success')
        with patch.object(host.shutil, 'which', lambda name: None):
            try:
                host.execute_hotkey('A')
            except RuntimeError:
                pass
            else:
                raise AssertionError('Missing xdotool reported success')
    assert not host._KEY_COUNTS
    print('PASS PC input: Windows/reverse releases, Mac F13/unsupported holds, X11/missing backend/Wayland errors, overlapping client ownership, partial failure rollback and actual framed disconnect cleanup (all OS input mocked)')


def static_checks():
    for app in ('authenticator', 'passwords'):
        root = ET.parse(ROOT/f'split-apps/{app}/AndroidManifest.xml').getroot()
        permissions = {p.get(NS+'name') for p in root.findall('uses-permission')}
        assert 'android.permission.INTERNET' not in permissions
        assert root.find('application').get(NS+'allowBackup') == 'false'
        assert root.get('package') == f'ca.arc7freedom.{app}'
    vault = (SRC/'Arc7VaultActivity.java').read_text()
    assert 'FLAG_SECURE' in vault and 'onStop()' in vault and 'lock(false)' in vault
    assert 'synchronized(vaultIo)' in vault and 'request!=generation' in vault
    assert 'putString(' not in vault and 'outState.put' not in vault
    assert 'setSaveEnabled(false)' in vault and '30000' in vault and '120000' in vault
    assert 'Import Proton Pass / Bitwarden export' in vault and 'Arc7VaultImporter.merge' in vault
    assert 'independent security audit' in vault and 'there is no recovery' in vault.lower()
    importer = (SRC/'Arc7VaultImporter.java').read_text()
    assert 'MAX_IMPORT_BYTES' in importer and 'object.opt("encrypted")' in importer
    assert 'Arc7Totp.parse(totp)' in importer and '"logintotp"' in importer
    file_share = (SRC/'Arc7QrFileShareServer.java').read_text()
    assert 'new InetSocketAddress(InetAddress.getByName("0.0.0.0")' in file_share
    assert 'SameSite=Strict' in file_share and 'HttpOnly' in file_share and 'getCanonicalFile()' in file_share
    assert 'never overwritten' in file_share.lower()
    file_ui = (SRC/'Arc7FileManagerActivity.java').read_text()
    assert 'QR File Share' in file_ui and 'QrCodeEncoder.renderQrBitmap(address' in file_ui
    assert 'unencrypted HTTP' in file_ui and 'Arc7QrFileShareService.startShare' in file_ui
    qr_home = (SRC/'Arc7QrTransferHomeActivity.java').read_text()
    assert 'Intent.ACTION_SEND_MULTIPLE' in qr_home and 'getClipData()' in qr_home and 'acceptSharedFile' in qr_home
    qr_manifest = ET.parse(ROOT/'split-apps/qrtransfer/AndroidManifest.xml').getroot()
    assert any(action.get(NS+'name') == 'android.intent.action.SEND_MULTIPLE'
               for action in qr_manifest.findall('.//activity/intent-filter/action'))
    share_manifest = ET.parse(ROOT/'split-apps/filemanager/AndroidManifest.xml').getroot()
    assert any(c.get(NS+'name') == 'ca.arc7freedom.app.Arc7QrFileShareService' for c in share_manifest.findall('application/service'))
    shared = (SRC/'Arc7Skin.java').read_text()
    assert 'showQuickAppBarSwitcher' not in shared and '.setColors(' not in shared
    launcher = (ROOT/'launcher-android4/src/main/java/ca/arc7freedom/launcher/VanillaHomeActivity.java').read_text()
    assert 'Show Quick Search Button on Top Bar' in launcher and 'In-App Header Style' in launcher
    assert 'Switcher Pill' not in launcher
    deck = (SRC/'Arc7StreamDeckActivity.java').read_text()
    assert 'ThreadPoolExecutor(1,1' in deck and 'ArrayBlockingQueue<Runnable>(128)' in deck
    assert 'activeSocket!=socket' in deck and 'closeSocketIf' in deck and 'packets.shutdownNow()' in deck
    key_ui = deck.split('private FrameLayout buildLcdStreamDeckKey(', 1)[1].split('private GradientDrawable makeKeyLcdDrawable', 1)[0]
    assert 'new Arc7DeckKeyInput(key.mode' in key_ui and 'return input.touch(act, editKeysMode)' in key_ui
    assert 'outer.setLongClickable(editKeysMode)' in key_ui and 'if (!editKeysMode) return false;' in key_ui
    assert key_ui.index('if (editKeysMode) {\n            outer.setOnLongClickListener') < key_ui.index('return input.longClick(editKeysMode)')
    assert 'if (!editKeysMode) return;' in deck.split('private void showEditKeyDialog(', 1)[1].split('ScrollView sv', 1)[0]
    assert 'cancelHeldDeckKeys();' in deck.split('private void renderDeckMatrix()', 1)[1].split('int rows', 1)[0]
    assert 'cancelHeldDeckKeys();' in deck.split('@Override protected void onStop()', 1)[1]
    for name in ('usb-sideload-and-root', 'Backup-Kobo-To-Laptop-ADB'):
        for ext in ('sh', 'bat'):
            assert 'show_install_patches' not in (ROOT/f'tools/{name}.{ext}').read_text()
    main = (SRC/'MainActivity.java').read_text()
    assert 'new InstallMaintenancePanel' in main and 'new TextView[6]' in main and '"PATCHES"' in main
    assert '"Podcasts.apk"' in (ROOT/'scripts/gen-split-apps.py').read_text()
    panel = (SRC/'InstallMaintenancePanel.java').read_text()
    assert 'closed || !resumed || request!=choiceRequest' in panel and 'if (!resumed) return;' in panel
    assert 'maintenance.close()' in main
    music = (SRC/'YouTubeMusicActivity.java').read_text()
    assert 'if (!alive || !bound) return;' in music and 'bound=bindService(' in music
    assert 'request!=lyricsRequest' in music and 'snap.title+"\\n"+snap.artist' in music
    assert 'pendingMusicQueue=null' in music and 'resetLyrics();' in music and 'ui.removeCallbacksAndMessages(null)' in music
    assert 'finally { MorpheEngine.cancelPlayback(resolved); }' in music
    assert 'showBrowsePane();' in music and 'showPlayerPane();' in music
    offline = (SRC/'OfflineMusicLibrary.java').read_text()
    assert 'compareText(a.author, b.author)' in offline and 'compareText(a.album, b.album)' in offline
    assert 'METADATA_KEY_DURATION' in offline and 'writeSidecar' in offline
    morphe = (SRC/'MorpheEngine.java').read_text()
    assert 'appendCloudSearchResults(items, out, musicOnly)' in morphe and 'new JSONArray(raw)' in morphe
    assert 'QUICK_CLOUD_ENDPOINT_ATTEMPTS = 2' in morphe and 'INVIDIOUS_INSTANCES[i]' in morphe
    audio = (SRC/'Arc7AudioService.java').read_text()
    assert 'restartCompletedPlayer(mp)' in audio and 'Repeat One' not in audio.split('restartCompletedPlayer',1)[0]
    print('PASS integration guards: separate offline vault identities/backup policy, secret lifecycle/commit gates, no Quick Switcher, retained Home controls, ordered deck writer, Music offline metadata/repeat/pane routing and embedded PATCHES/no forced PC screen')


def main():
    static_checks()
    check_host_keys()
    tools = Path(os.environ.get('TOOLCHAIN_DIR', ROOT/'build/toolchain'))
    java = os.environ.get('JAVA') or (str(tools/'jdk/bin/java') if (tools/'jdk/bin/java').exists() else shutil.which('java'))
    javac = os.environ.get('JAVAC') or (str(tools/'jdk/bin/javac') if (tools/'jdk/bin/javac').exists() else shutil.which('javac'))
    assert java and javac, 'Install a JDK / bootstrap the toolchain'
    with tempfile.TemporaryDirectory(prefix='arc7-features-') as tmp:
        tmp = Path(tmp)
        files = []
        for name, content in STUBS.items():
            file = tmp/name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(content)
            files.append(file)
        files += list((ROOT/'tests/support/org/json').glob('*.java'))
        files += [SRC/name for name in ('Arc7VaultCrypto.java', 'Arc7Totp.java', 'Arc7VaultImporter.java', 'Arc7QrFileShareServer.java', 'Arc7ResumableUploads.java', 'Arc7KeyBinding.java', 'Arc7DeckKeyInput.java', 'LegacyMediaPolicy.java', 'LegacyMediaIo.java', 'LegacyAacConfig.java', 'LegacyTsProbe.java', 'Arc7DownloadUi.java',
                                       'MorpheEngine.java', 'MorpheRemuxer.java', 'MorpheHls.java', 'MorpheHlsRelay.java', 'MorpheStreamServer.java')]
        files += [ROOT/'shared/java/ca/arc7freedom/support'/name for name in ('DebugLog.java','RuntimePolicy.java','MediaCache.java')]
        files += [ROOT/'tests/java'/name for name in ('SuiteFeatureHarness.java', 'DeckKeyInputHarness.java', 'MediaIoHarness.java')]
        classes = tmp/'classes'
        classes.mkdir()
        subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(classes), *map(str, files)], check=True)
        output = tmp/'output'
        for harness in ('DeckKeyInputHarness', 'MediaIoHarness'):
            subprocess.run([java, '-ea', '-cp', str(classes), 'ca.arc7freedom.app.'+harness], check=True, timeout=20)
        cc = shlex.split(os.environ['CC']) if os.environ.get('CC') else [shutil.which('cc') or shutil.which('clang') or 'cc']
        native = tmp/'media-send'
        subprocess.run([*cc, '-std=c99', '-Wall', '-Wextra', '-Werror', '-I'+str(ROOT/'native'), str(ROOT/'tests/native/media-send.c'), '-o', str(native)], check=True, timeout=30)
        subprocess.run([str(native)], check=True, timeout=5)
        tls = (ROOT/'native/arc7tls.c').read_text()
        bridge = tls.split('static void stream_https_bridge(', 1)[1].split('Local 127.0.0.1 TLS 1.3 Web', 1)[0]
        assert '#include "arc7socket.h"' in tls
        assert 'send(client_fd,' not in bridge and bridge.count('arc7_send_all(') == 4
        subprocess.run([java, '-ea', '-cp', str(classes), 'ca.arc7freedom.app.SuiteFeatureHarness', str(output), str(ROOT/'tests/fixtures/morphe-hls')], check=True, timeout=90)
        ffmpeg = os.environ.get('FFMPEG') or shutil.which('ffmpeg')
        if not ffmpeg:
            try:
                import imageio_ffmpeg
                ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
            except ImportError:
                pass
        if ffmpeg:
            for file in ('validated-video.mp4', 'validated-audio.m4a'):
                subprocess.run([ffmpeg, '-nostdin', '-hide_banner', '-loglevel', 'error', '-xerror', '-i', str(output/file), '-f', 'null', '-'], check=True, timeout=15)
            print('PASS actual downloaded MP4/M4A decoded by host FFmpeg (synthetic H.264/AAC, not Kobo decoder validation)')
        else:
            print('NOTE optional host decoder skipped; set FFMPEG or install imageio-ffmpeg')
    print('ALL OFFLINE SUITE FEATURE REGRESSIONS PASSED (no device/live-provider/security-audit claim)')


if __name__ == '__main__':
    main()
