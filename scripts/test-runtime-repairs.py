#!/usr/bin/env python3
"""Offline regressions for media ownership, legacy launcher management, Wi-Fi proxy repair,
and real H.264/AAC HLS -> loopback TS / classic MP4/M4A. No YouTube access or Kobo is implied.

Optional: FFMPEG=/path/to/ffmpeg checks decoding of the resulting files as well as byte/range
integrity. The tiny checked-in media fixtures are generated test patterns, not YouTube media.
"""
from pathlib import Path
import importlib.util
import http.server
import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
import urllib.parse

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/ca/arc7freedom/app"
FIXTURES = ROOT / "tests/fixtures/morphe-hls"

HLS_HARNESS = r'''package ca.arc7freedom.app;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class HlsRepairHarness {
    static final String BASE = "https://fixture.test/media/";
    static File fixtures, output;
    static final Map<String,byte[]> data = new HashMap<String,byte[]>();
    static final Map<String,Integer> hits = new HashMap<String,Integer>();
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static byte[] bytes(String value) throws Exception { return value.getBytes("UTF-8"); }
    static void put(String name, String value) throws Exception { data.put(BASE+name, bytes(value)); }
    static final MorpheHls.Transport transport = new MorpheHls.Transport() {
        public MorpheHls.Response open(String url, String range) throws Exception {
            synchronized(hits) { hits.put(url, hits.containsKey(url) ? hits.get(url)+1 : 1); }
            byte[] body = data.get(url);
            if (body == null) {
                if (!url.startsWith(BASE) || url.substring(BASE.length()).contains("..")) throw new IOException("Unexpected upstream: " + url);
                File file = new File(fixtures,url.substring(BASE.length()).split("\\?")[0]);
                if (!file.isFile()) return new MorpheHls.Response(403,"text/plain",0,null,new ByteArrayInputStream(new byte[0]));
                body = Files.readAllBytes(file.toPath());
            }
            long total = body.length;
            String contentRange = null;
            if (range != null) {
                String[] parts = range.substring(6).split("-",-1);
                int start = parts[0].length() == 0 ? 0 : Integer.parseInt(parts[0]);
                int end = parts[1].length() == 0 ? body.length-1 : Math.min(Integer.parseInt(parts[1]),body.length-1);
                body = Arrays.copyOfRange(body,start,end+1);
                contentRange = "bytes "+start+"-"+end+"/"+total;
            }
            return new MorpheHls.Response(range == null ? 200 : 206,"application/octet-stream",body.length,
                    contentRange,new ByteArrayInputStream(body));
        }
    };
    static byte[] get(String url, String range, String method, int expected) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        c.setRequestMethod(method); if (range != null) c.setRequestProperty("Range",range);
        c.setConnectTimeout(5000); c.setReadTimeout(5000);
        try {
            check(c.getResponseCode()==expected,"Local HTTP "+c.getResponseCode()+" expected "+expected);
            if (expected == 206) check(c.getHeaderField("Content-Range") != null,"Missing Content-Range");
            InputStream in = expected >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int n;
            if (in != null) { while ((n=in.read(buffer))>=0) out.write(buffer,0,n); in.close(); }
            return out.toByteArray();
        } finally { c.disconnect(); }
    }
    static String firstUri(String manifest) {
        for (String line : manifest.split("\n")) if (!line.startsWith("#") && line.trim().length()>0) return line.trim();
        throw new AssertionError("No local media URI");
    }
    static void remux(String master, boolean audioOnly, String name) throws Exception {
        MorpheHls.Selection selected=MorpheHls.select(transport,BASE+master,audioOnly);
        MorpheHls.probe(transport,selected.primary());
        MorpheRemuxer.SegmentSource v=selected.video != null ? MorpheHls.source(transport,selected.video) : null;
        MorpheRemuxer.SegmentSource a=selected.audio != null ? MorpheHls.source(transport,selected.audio) : null;
        File file=new File(output,name);
        RandomAccessFile out=new RandomAccessFile(file,"rw");
        try { MorpheRemuxer.remuxHlsClassicMp4(v,a,out,null); } finally { out.close(); }
        byte[] body=Files.readAllBytes(file.toPath());
        check(body.length>1000,"No actual media was written");
        check(new String(body,4,4,"ISO-8859-1").equals("ftyp"),"Not classic MP4");
    }
    static void pendingAndCancellation(MorpheHls.Selection selected) throws Exception {
        long originalWait = MorpheStreamServer.preparationWaitMs;
        MorpheStreamServer.preparationWaitMs = 80L;
        for (final boolean inputProgress : new boolean[]{false,true}) {
            final CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
            final Thread[] worker = new Thread[1];
            String pending = MorpheStreamServer.register(0,new MorpheStreamServer.JobBody() {
                public void run(RandomAccessFile out,MorpheRemuxer.Progress progress) throws Exception {
                    worker[0] = Thread.currentThread();
                    out.write(new byte[]{1,2,3,4,5,6,7,8});
                    if(inputProgress) progress.onBytes(4096,8192);
                    entered.countDown(); finish.await();
                    out.seek(0); out.write(new byte[16]);
                }
            },-1L,"audio/mp4");
            check(entered.await(2,TimeUnit.SECONDS),"Pending job never started");
            check(get(pending,null,"GET",503).length==0,"Partial output was advertised as a complete file");
            if(inputProgress) {
                MorpheStreamServer.cancel(pending); worker[0].join(2000);
                check(!worker[0].isAlive(),"In-flight preparation was not interrupted");
                check(!new File(output,"morphe_"+pending.substring(pending.lastIndexOf('/')+1)+".mp4").exists(),"Cancelled cache survived");
                get(pending,null,"GET",404);
            } else {
                finish.countDown(); worker[0].join(2000);
                check(get(pending,null,"GET",200).length==16,"Completed output has the wrong final length");
                MorpheStreamServer.cancel(pending);
            }
        }
        MorpheStreamServer.preparationWaitMs = originalWait;
        final CountDownLatch writing = new CountDownLatch(1), blocked = new CountDownLatch(1);
        final Thread[] streamingWorker = new Thread[1];
        String streaming = MorpheStreamServer.register(0,new MorpheStreamServer.JobBody() {
            public void run(RandomAccessFile out,MorpheRemuxer.Progress progress) throws Exception {
                streamingWorker[0] = Thread.currentThread(); out.write(123); progress.onBytes(1,10000);
                writing.countDown(); blocked.await();
            }
        },10000L,"video/mp4");
        check(writing.await(2,TimeUnit.SECONDS),"Streaming job never started");
        HttpURLConnection streamingClient=(HttpURLConnection)new URL(streaming).openConnection(java.net.Proxy.NO_PROXY);
        streamingClient.setReadTimeout(2000);
        InputStream body=streamingClient.getInputStream();
        check(body.read()==123,"Committed streaming byte missing");
        MorpheStreamServer.cancel(streaming); streamingWorker[0].join(2000);
        try { check(body.read()==-1,"Cancelled streaming socket remained open"); } catch(IOException closed) {}
        body.close(); streamingClient.disconnect();
        check(!streamingWorker[0].isAlive(),"Streaming worker was not cancelled");
        get(streaming,null,"GET",404);

        final CountDownLatch reading=new CountDownLatch(1), closed=new CountDownLatch(1), ended=new CountDownLatch(1);
        MorpheHls.Transport slow=new MorpheHls.Transport() {
            public MorpheHls.Response open(String url,String range) throws Exception {
                return new MorpheHls.Response(200,"video/mp2t",10000,null,new InputStream() {
                    public int read() throws IOException {
                        reading.countDown();
                        try { closed.await(); throw new IOException("closed"); }
                        catch(InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new IOException("cancelled",cancelled); }
                        finally { ended.countDown(); }
                    }
                    public void close() { closed.countDown(); }
                });
            }
        };
        final String slowUrl=MorpheHlsRelay.register(selected,slow);
        final String slowSegment=firstUri(new String(get(slowUrl,null,"GET",200),"UTF-8"));
        Thread reader=new Thread(new Runnable() {
            public void run() { try { get(slowSegment,null,"GET",200); } catch(Exception cancelled) {} }
        });
        reader.start(); check(reading.await(2,TimeUnit.SECONDS),"HLS segment read did not start");
        MorpheHlsRelay.cancel(slowUrl); reader.join(2000);
        check(!reader.isAlive() && ended.await(2,TimeUnit.SECONDS) && closed.await(2,TimeUnit.SECONDS),"In-flight HLS client/transport survived cancellation");
        get(slowUrl,null,"GET",404);
        System.out.println("PASS: unknown final length/input progress never serves partial files; timeout is 503; in-flight remux and HLS cancellation close clients/workers and remove cache");
    }
    public static void main(String[] args) throws Exception {
        fixtures=new File(args[0]); output=new File(args[1]);
        MorpheStreamServer.setCacheDir(output);
        String prefix="#EXTM3U\n#EXT-X-VERSION:7\n";
        put("ts-master.m3u8",prefix
            +"#EXT-X-STREAM-INF:BANDWIDTH=4000000,CODECS=\"av01.0.08M.08,mp4a.40.2\",RESOLUTION=1920x1080\nunsupported.m3u8\n"
            +"#EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS=\"avc1.64001f,mp4a.40.2\",RESOLUTION=1280x720,FRAME-RATE=60\nunsupported.m3u8\n"
            +"#EXT-X-STREAM-INF:BANDWIDTH=200000,CODECS=\"avc1.42c00a,mp4a.40.2\",RESOLUTION=64x48,FRAME-RATE=10\nlegacy.m3u8\n");
        put("separate-master.m3u8",prefix
            +"#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aac\",NAME=\"English, original\",DEFAULT=YES,URI=\"audio.m3u8\"\n"
            +"#EXT-X-STREAM-INF:BANDWIDTH=200000,CODECS=\"avc1.42c00a,mp4a.40.2\",AUDIO=\"aac\",RESOLUTION=64x48\nvideo.m3u8\n");
        put("muxed-master.m3u8",prefix+"#EXT-X-STREAM-INF:BANDWIDTH=200000,CODECS=\"avc1.42c00a,mp4a.40.2\",RESOLUTION=64x48\nmuxed.m3u8\n");
        MorpheHls.Selection selected=MorpheHls.select(transport,BASE+"ts-master.m3u8",false);
        check(selected.primary().url.endsWith("legacy.m3u8"),"Unsupported codec/60fps was selected");
        MorpheHls.probe(transport,selected.primary());
        String local=MorpheHlsRelay.register(selected,transport);
        check(local.startsWith("http://127.0.0.1:") && local.endsWith(".m3u8"),"Not a private HLS URL");
        String manifest=new String(get(local,null,"GET",200),"UTF-8");
        check(!manifest.contains("https://") && !manifest.contains("legacy-"),"Unbridged URI leaked to Stagefright");
        String segment=firstUri(manifest);
        byte[] expected=Files.readAllBytes(new File(fixtures,"legacy-00.ts").toPath());
        check(Arrays.equals(get(segment,null,"GET",200),expected),"TS segment changed");
        check(Arrays.equals(get(segment,"bytes=8-39","GET",206),Arrays.copyOfRange(expected,8,40)),"TS range changed");
        check(get(segment,null,"HEAD",200).length==0,"HEAD returned a body");
        get(segment,"bytes=0-0,2-2","GET",416);
        get(local.replace("/hls/","/wrong/"),null,"GET",404);
        String rewritten=MorpheHls.rewrite(MorpheHls.parseMedia(BASE+"keys/playlist.m3u8",
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin?sig=a,b\"\n#EXTINF:1,\n../legacy-00.ts?sig=a%2Fb\n#EXT-X-ENDLIST\n"),
            new MorpheHls.UriMapper() { public String local(String uri,boolean playlist) {
                check(uri.startsWith(BASE),"Relative HLS URI was resolved incorrectly"); return "http://127.0.0.1/rewritten";
            }});
        check(!rewritten.contains("../") && !rewritten.contains("https://"),"AES key URI was not rewritten");
        MorpheHls.Playlist ranged=MorpheHls.parseMedia(BASE+"ranges.m3u8",
            "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"32@8\"\n#EXTINF:1,\n#EXT-X-BYTERANGE:16@40\nbody.mp4\n"
            +"#EXTINF:1,\n#EXT-X-BYTERANGE:16\nbody.mp4\n#EXT-X-ENDLIST\n");
        check("bytes=8-39".equals(ranged.init.range) && "bytes=56-71".equals(ranged.segments.get(1).range),"HLS byte ranges lost");
        put("html.m3u8","<html>metadata is not playable media</html>");
        boolean failed=false; try { MorpheHls.select(transport,BASE+"html.m3u8",false); } catch(IOException ok) { failed=true; }
        check(failed,"Metadata/error page accepted as media");
        for (String method:new String[]{"SAMPLE-AES","AES-CTR"}) {
            failed=false; try { MorpheHls.parseMedia(BASE+"drm.m3u8","#EXTM3U\n#EXT-X-KEY:METHOD="+method+",URI=\"key\"\n#EXTINF:1,\nx.ts\n#EXT-X-ENDLIST\n"); }
            catch(IOException ok) { failed=true; } check(failed,"DRM was not rejected");
        }
        MorpheHls.Playlist unavailable=MorpheHls.parseMedia(BASE+"missing.m3u8","#EXTM3U\n#EXTINF:1,\nmissing.ts\n#EXT-X-ENDLIST\n");
        failed=false; try { MorpheHls.probe(transport,unavailable); } catch(IOException ok) { failed=true; }
        check(failed,"403 media was accepted");
        String liveRaw=new String(Files.readAllBytes(new File(fixtures,"legacy.m3u8").toPath()),"UTF-8").replace("#EXT-X-ENDLIST","");
        put("live.m3u8",liveRaw);
        MorpheHls.Selection live=MorpheHls.select(transport,BASE+"live.m3u8",false);
        String liveUrl=MorpheHlsRelay.register(live,transport);
        get(liveUrl,null,"GET",200); get(liveUrl,null,"GET",200);
        check(hits.get(BASE+"live.m3u8")==3,"Live playlist was incorrectly cached");
        remux("separate-master.m3u8",false,"separate.mp4");
        remux("muxed-master.m3u8",false,"muxed.mp4");
        remux("separate-master.m3u8",true,"audio.m4a");
        remux("muxed-master.m3u8",true,"muxed-audio.m4a");
        final byte[] classic=Files.readAllBytes(new File(output,"muxed.mp4").toPath());
        String url=MorpheStreamServer.register(0,new MorpheStreamServer.JobBody() {
            public void run(RandomAccessFile out,MorpheRemuxer.Progress progress) throws Exception { out.write(classic); }
        },-1L,"video/mp4");
        check(Arrays.equals(classic,get(url,null,"GET",200)),"Classic HLS output was not served completely");
        check(Arrays.equals(Arrays.copyOfRange(classic,31,313),get(url,"bytes=31-312","GET",206)),"Classic HLS Range mismatch");
        String broken=MorpheStreamServer.register(0,new MorpheStreamServer.JobBody() {
            public void run(RandomAccessFile out,MorpheRemuxer.Progress progress) throws Exception { throw new IOException("fixture media 403"); }
        },-1L,"video/mp4");
        get(broken,null,"GET",502);
        check(MorpheStreamServer.getFailure(broken).contains("fixture media 403"),"Preparation error was swallowed");
        MorpheHlsRelay.cancel(local); get(local,null,"GET",404);
        pendingAndCancellation(selected);
        MorpheStreamServer.cancelAll();
        System.out.println("PASS: real H.264/AAC TS + muxed/separate fMP4 HLS, audio-only M4A, strict ranges, HEAD, key/segment URI rewriting, live refresh, 403/DRM/error rejection");
    }
}'''

POLICY_HARNESS = r'''package ca.arc7freedom.app;
import android.content.*;
import android.net.wifi.*;
public class RuntimePolicyHarness {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        for (String pkg:new String[]{"ca.arc7freedom.app","ca.arc7freedom.clock","ca.arc7freedom.audioplayer","ca.arc7freedom.youtube","ca.arc7freedom.ytmusic"}) {
            check(!MediaNotificationPolicy.mayPost(pkg,"org.videolan.vlc",true,true,true));
            check(!MediaNotificationPolicy.mayPost(pkg,pkg,true,false,false));
            check(!MediaNotificationPolicy.mayPost(pkg,pkg,false,true,true));
            check(MediaNotificationPolicy.mayPost(pkg,pkg,true,true,false));
            check(MediaNotificationPolicy.mayPost(pkg,pkg,true,false,true));
            check(!MediaNotificationPolicy.mayPost(pkg,"ca.arc7freedom.radiorss",true,true,false));
        }
        check(!MediaNotificationPolicy.ownsSession(null,null,true));
        check(LegacyWifiProxyCleanup.isLegacy("LOCALHOST",8118));
        check(!LegacyWifiProxyCleanup.isLegacy("127.0.0.1.work.test",8118));
        WifiManager wifi=new WifiManager(); Context ctx=new Context(wifi);
        wifi.networks.add(new WifiConfiguration(1,"127.0.0.1",8118));
        wifi.networks.add(new WifiConfiguration(2,"localhost",8118));
        wifi.networks.add(new WifiConfiguration(3,"proxy.school.test",3128));
        wifi.networks.add(new WifiConfiguration(4,"127.0.0.1",9000));
        LegacyWifiProxyCleanup.Result result=LegacyWifiProxyCleanup.clear(ctx);
        check(result.complete() && result.matched==2 && result.cleared==2 && wifi.updates==2);
        check(wifi.networks.get(0).proxySettings==WifiConfiguration.ProxySettings.NONE);
        check(wifi.networks.get(0).linkProperties.ip.equals("192.0.2.5"));
        check(wifi.networks.get(0).preSharedKey.equals("unchanged"));
        check(wifi.networks.get(2).linkProperties.getHttpProxy().getHost().equals("proxy.school.test"));
        check(wifi.networks.get(3).linkProperties.getHttpProxy().getPort()==9000);
        check(LegacyWifiProxyCleanup.clear(ctx).complete() && wifi.updates==2);
        wifi.networks.add(new WifiConfiguration(5,"localhost",8118)); wifi.denyUpdate=true;
        check(!LegacyWifiProxyCleanup.clear(ctx).complete());
        check(wifi.networks.get(4).linkProperties.getHttpProxy()!=null);
        wifi.denyUpdate=false; wifi.saveOk=false;
        check(!LegacyWifiProxyCleanup.clear(ctx).complete());
        wifi.denyRead=true; check(!LegacyWifiProxyCleanup.clear(ctx).complete());
        // Clearing two saved Wi-Fi proxies must NOT hide an independent global proxy.
        android.provider.Settings.Global.values.put("http_proxy","127.0.0.1:8118");
        check(!LegacyWifiProxyCleanup.globalAndActiveProxyAreClear(ctx));
        android.provider.Settings.Global.values.clear();
        android.provider.Settings.Global.values.put("global_http_proxy_host","localhost");
        android.provider.Settings.Global.values.put("global_http_proxy_port","8118");
        check(!LegacyWifiProxyCleanup.globalAndActiveProxyAreClear(ctx));
        android.provider.Settings.Global.values.put("http_proxy","proxy.school.test:3128");
        check(LegacyWifiProxyCleanup.globalAndActiveProxyAreClear(ctx));
        android.provider.Settings.Global.values.clear();
        android.net.Proxy.host="127.0.0.1"; android.net.Proxy.port=8118;
        check(!LegacyWifiProxyCleanup.globalAndActiveProxyAreClear(ctx));
        android.net.Proxy.port=9000; check(LegacyWifiProxyCleanup.globalAndActiveProxyAreClear(ctx));
        android.provider.Settings.Global.denied=true;
        check(!LegacyWifiProxyCleanup.globalAndActiveProxyAreClear(ctx));
        System.out.println("PASS: notifications owned by actual prepared players only; exact old Wi-Fi proxy cleanup, retry/idempotence, preserved credentials/IP/work proxies, denied APIs reported");
    }
}'''

MANAGEMENT_HARNESS = r'''package ca.arc7freedom.launcher;
import android.content.*;
public class ManagementRepairHarness {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        String suite="ca.arc7freedom.app";
        check("ca.arc7freedom.packageinstaller".equals(BuiltinApps.managementPackage(suite,".launch.Arc7PackageInstaller")));
        check("ca.arc7freedom.packageinstaller".equals(BuiltinApps.managementPackage(suite,suite+".Arc7PackageInstallerActivity")));
        check("ca.arc7freedom.packageinstaller".equals(BuiltinApps.managementPackage("ca.arc7freedom.packageinstaller",suite+".Arc7PackageInstallerActivity")));
        check("ca.arc7freedom.upgrader".equals(BuiltinApps.managementPackage("ca.arc7freedom.upgrader","ca.arc7freedom.upgrader.UpgraderActivity")));
        check(suite.equals(BuiltinApps.managementPackage(suite,suite+".MainActivity")));
        check("foreign.app".equals(BuiltinApps.managementPackage("foreign.app",suite+".Arc7PackageInstallerActivity")));
        Intent old=new Intent("MAIN").setComponent(new ComponentName(suite,suite+".launch.Arc7PackageInstaller"));
        check(BuiltinApps.route(old).getComponent().getPackageName().equals(BuiltinApps.managementPackage(suite,suite+".launch.Arc7PackageInstaller")));
        System.out.println("PASS: legacy Package Installer App Info/uninstall resolves the same real owner as launch; suite/upgrader/foreign identities are not substituted");
    }
}'''


COMPILED_ENGINE_HARNESS = r'''import ca.arc7freedom.app.*;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
public class CompiledHlsBindingHarness {
    static byte[] read(String url) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout(5000); connection.setReadTimeout(10000);
        if(connection.getResponseCode()!=200) throw new AssertionError("Local player HTTP "+connection.getResponseCode());
        InputStream in=connection.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buffer=new byte[8192]; int n; while((n=in.read(buffer))>=0) out.write(buffer,0,n);
        in.close(); connection.disconnect(); return out.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        MorpheEngine.setGatewayPortForTest(Integer.parseInt(args[0]));
        MorpheStreamServer.setCacheDir(new File(args[1]));
        Method bind=MorpheEngine.class.getDeclaredMethod("bindLocalPlayback",android.content.Context.class,
                MorpheEngine.ResolvedPlayback.class,boolean.class);
        bind.setAccessible(true);
        Field clients=MorpheEngine.class.getDeclaredField("CLIENT_SPECS"); clients.setAccessible(true);
        String[] vision=((String[][])clients.get(null))[0];
        Method visitor=MorpheEngine.class.getDeclaredMethod("ensureClientVisitorData",android.content.Context.class,String[].class,int.class);
        visitor.setAccessible(true);
        String visitorData=(String)visitor.invoke(null,null,vision,4000);
        if(!"fixture-visitor-token-0001".equals(visitorData)) throw new AssertionError("Per-client visitor request failed");
        Method player=MorpheEngine.class.getDeclaredMethod("fetchPlayerWithClientSpec",android.content.Context.class,String.class,String[].class,String.class,int.class);
        player.setAccessible(true);
        MorpheEngine.ResolvedPlayback ts=(MorpheEngine.ResolvedPlayback)player.invoke(null,null,"fixture0001",vision,visitorData,4000);
        if(ts == null || !ts.progressiveVideos.get(0).directHttpsUrl.matches(".*[?&]cpn=[A-Za-z0-9_-]{16}$"))
            throw new AssertionError("Player identity/CPN stream binding failed");
        bind.invoke(null,null,ts,false);
        String playlist=new String(read(ts.localVideoProxyUrl),"UTF-8"), segment="";
        for(String line:playlist.split("\n")) if(!line.startsWith("#") && line.length()>0) { segment=line; break; }
        if(!Arrays.equals(read(segment),Files.readAllBytes(new File(args[2],"legacy-00.ts").toPath())))
            throw new AssertionError("Compiled engine HLS media mismatch");
        MorpheEngine.ResolvedPlayback audio=new MorpheEngine.ResolvedPlayback();
        audio.hlsManifestUrl="https://fixture.test/media/muxed.m3u8"; audio.hlsUserAgent=ts.hlsUserAgent;
        bind.invoke(null,null,audio,true);
        byte[] m4a=read(audio.localAudioProxyUrl);
        if(m4a.length<1000 || !new String(m4a,4,4,"ISO-8859-1").equals("ftyp")) throw new AssertionError("No playable M4A");
        MorpheEngine.cancelPlayback(ts); MorpheEngine.cancelPlayback(audio);
        final MorpheEngine.ResolvedPlayback interrupted=new MorpheEngine.ResolvedPlayback();
        interrupted.hlsManifestUrl="https://fixture.test/media/cancel.m3u8"; interrupted.hlsUserAgent=ts.hlsUserAgent;
        final Method binder=bind;
        final Throwable[] failure=new Throwable[1];
        Thread resolver=new Thread(new Runnable() {
            public void run() { try { binder.invoke(null,null,interrupted,false); } catch(Throwable error) { failure[0]=error; } }
        });
        resolver.start();
        String control="http://127.0.0.1:"+args[0]+"/__test/";
        read(control+"wait-start"); resolver.interrupt(); read(control+"release"); resolver.join(3000);
        if(resolver.isAlive() || failure[0] != null || !interrupted.localVideoProxyUrl.isEmpty())
            throw new AssertionError("Interrupted in-flight resolver registered playback",failure[0]);
        System.out.println("PASS: compiled MorpheEngine visitor/player request contract -> HLS video/audio via its owning TLS gateway; interrupted in-flight resolution starts no new media requests");
    }
}'''


def check_compiled_binding(tmp, classes, javac, java, toolchain):
    compiled = ROOT / "build/app/classes"
    if not compiled.exists():
        print("NOTE: compiled MorpheEngine integration skipped; build the APKs to enable it")
        return
    seen, requests = [], []
    cancel_started, cancel_release = threading.Event(), threading.Event()
    expected_ua = "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; US)"

    class GatewayFixture(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            try:
                body = json.loads(raw)
            except (ValueError, UnicodeDecodeError):
                self.send_error(400, "Invalid JSON request")
                return
            requests.append((self.path, dict(self.headers), body))
            payload = {"responseContext": {"visitorData": "fixture-visitor-token-0001"}}
            if "/player?" in self.path:
                payload = {"playabilityStatus": {"status": "OK"}, "videoDetails": {
                    "videoId": "fixture0001", "title": "Real fixture media", "lengthSeconds": "3"},
                    "streamingData": {"hlsManifestUrl": "https://fixture.test/media/legacy.m3u8",
                        "formats": [{"itag": 18, "mimeType": 'video/mp4; codecs="avc1.42c00a, mp4a.40.2"',
                            "url": "https://fixture.test/media/progressive.mp4"}]}}
            data = json.dumps(payload).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            prefix = "/__https/fixture.test/media/"
            path = urllib.parse.urlsplit(self.path).path
            if path.startswith("/__test/"):
                if path.endswith("wait-start") and not cancel_started.wait(5):
                    self.send_error(504)
                    return
                if path.endswith("release"):
                    cancel_release.set()
                self.send_response(200)
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.write(b"OK")
                return
            if path == prefix + "cancel.m3u8":
                cancel_started.set()
                cancel_release.wait(5)
                path = prefix + "legacy.m3u8"
            if not path.startswith(prefix):
                self.send_error(404)
                return
            name = path[len(prefix):]
            if "/" in name or ".." in name or not (FIXTURES / name).is_file():
                self.send_error(404)
                return
            seen.append((path, self.headers.get("User-Agent")))
            body = (FIXTURES / name).read_bytes()
            length = len(body)
            request_range = self.headers.get("Range")
            status, content_range = 200, None
            if request_range:
                a, b = request_range[6:].split("-", 1)
                start, end = int(a or 0), min(int(b) if b else length - 1, length - 1)
                body, status = body[start:end + 1], 206
                content_range = f"bytes {start}-{end}/{length}"
            self.send_response(status)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Content-Type", "application/vnd.apple.mpegurl" if name.endswith(".m3u8") else "application/octet-stream")
            if content_range:
                self.send_header("Content-Range", content_range)
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), GatewayFixture)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        driver = tmp / "CompiledHlsBindingHarness.java"
        driver.write_text(COMPILED_ENGINE_HARNESS)
        classpath = os.pathsep.join(map(str, (classes, compiled, toolchain / "sdk/platforms/android-17/android.jar")))
        run([javac, "-nowarn", "-cp", classpath, "-d", classes, driver])
        run([java, "-cp", classpath, "CompiledHlsBindingHarness", server.server_address[1], tmp / "output", FIXTURES], timeout=30)
        assert len(seen) >= 8 and all(ua == expected_ua for path, ua in seen), seen
        assert len(requests) == 2, requests
        visitor_path, visitor_headers, visitor_body = requests[0]
        assert visitor_path == "/__https/www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false"
        player_path, player_headers, player_body = requests[1]
        parts = urllib.parse.urlsplit(player_path)
        query = urllib.parse.parse_qs(parts.query)
        assert parts.path == "/__https/youtubei.googleapis.com/youtubei/v1/player"
        assert query["prettyPrint"] == ["false"] and query["id"] == ["fixture0001"]
        assert re.fullmatch(r"[A-Za-z0-9_-]{12}", query["t"][0]), query
        for headers, body in ((visitor_headers, visitor_body), (player_headers, player_body)):
            headers = {key.lower(): value for key, value in headers.items()}
            assert headers["user-agent"] == expected_ua
            assert headers["x-goog-api-format-version"] == "2" and "origin" not in headers
            client = body["context"]["client"]
            assert (client["clientName"], client["clientVersion"]) == ("VISIONOS", "1.04")
            assert client["clientScreen"] == "WATCH" and client["platform"] == "MOBILE"
            assert client["osVersion"] == "26.6.0.23O770" and client["utcOffsetMinutes"] == 0
        assert player_body["context"]["client"]["visitorData"] == "fixture-visitor-token-0001"
        assert player_body["contentCheckOk"] is True and player_body["racyCheckOk"] is True
        assert player_body["videoId"] == "fixture0001" and re.fullmatch(r"[A-Za-z0-9_-]{16}", player_body["cpn"])
        # Exactly one legacy TS fetch is the original playback probe; an interrupted
        # playlist read must not open another probe or register a local HLS session.
        assert sum(path.endswith("legacy-00.ts") for path, ua in seen) == 2, seen
    finally:
        cancel_release.set()
        server.shutdown()
        server.server_close()


def check_upgrader_commands(tmp):
    source = (ROOT / "upgrader/src/main/java/ca/arc7freedom/upgrader/UpgraderActivity.java").read_text()
    assert "BundledApk.installScript" in source and "installedMainMatches()" in source
    assert "pm uninstall" not in source and "pm clear" not in source
    assert 'Uri.parse("package:ca.arc7freedom.upgrader")' in source
    optimizer = (SRC / "TabletOptimizerManager.java").read_text()
    assert "BundledAppInstaller" in optimizer and "InstallMaintenanceService.request" in optimizer
    assert "pm uninstall ca.arc7freedom.launcher" not in optimizer
    print("PASS: updater uses the shared tested in-place installer, verifies installed bytes and opens the full patch pipeline")


def run(command, **kwargs):
    return subprocess.run([str(part) for part in command], check=True, **kwargs)


def main():
    toolchain = Path(os.environ.get("TOOLCHAIN_DIR", ROOT / "build/toolchain"))
    javac = os.environ.get("JAVAC") or (str(toolchain / "jdk/bin/javac") if (toolchain / "jdk/bin/javac").exists() else shutil.which("javac"))
    java = os.environ.get("JAVA") or (str(toolchain / "jdk/bin/java") if (toolchain / "jdk/bin/java").exists() else shutil.which("java"))
    assert java and javac, "Run scripts/bootstrap-toolchain.sh or install a JDK"
    with tempfile.TemporaryDirectory(prefix="arc7-runtime-") as directory:
        tmp = Path(directory)
        spec = importlib.util.spec_from_file_location("isolation_tests", ROOT / "scripts/test-app-isolation.py")
        isolation = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(isolation)
        stubs = {name: isolation.STUBS[name] for name in ("android/content/Intent.java", "android/content/ComponentName.java")}
        stubs.update({
            "android/content/Context.java": '''package android.content;
public class Context { public static final String WIFI_SERVICE="wifi"; Object wifi;
public Context(Object wifi) { this.wifi=wifi; } public Object getSystemService(String name) { return wifi; }
public ContentResolver getContentResolver() { return new ContentResolver(); } }''',
            "android/content/ContentResolver.java": "package android.content; public class ContentResolver {}",
            "android/net/Proxy.java": '''package android.net; public class Proxy {
public static String host; public static int port=-1;
public static String getDefaultHost() { return host; } public static int getDefaultPort() { return port; } }''',
            "android/provider/Settings.java": '''package android.provider; import java.util.*;
public class Settings { public static class Global {
public static Map<String,String> values=new HashMap<String,String>(); public static boolean denied;
public static String getString(android.content.ContentResolver resolver,String key) {
 if(denied) throw new SecurityException("global settings denied"); return values.get(key); }
} public static class Secure extends Global {} }''',
            "android/util/Log.java": '''package android.util; public class Log {
public static int i(String tag,String message) { return 0; } public static int w(String tag,String message) { return 0; }
}''',
            "android/net/wifi/WifiConfiguration.java": '''package android.net.wifi;
public class WifiConfiguration {
 public enum ProxySettings { NONE, STATIC }
 public ProxySettings proxySettings=ProxySettings.STATIC;
 public LinkProperties linkProperties; public int networkId;
 public String SSID="saved network", preSharedKey="unchanged";
 public WifiConfiguration(int id,String host,int port) { networkId=id; linkProperties=new LinkProperties(new ProxyProperties(host,port)); }
 public WifiConfiguration copy() { WifiConfiguration c=new WifiConfiguration(networkId,"",0); c.proxySettings=proxySettings;
 c.linkProperties=new LinkProperties(linkProperties.getHttpProxy()); c.SSID=SSID; c.preSharedKey=preSharedKey; return c; }
 public static class LinkProperties { ProxyProperties proxy; public String ip="192.0.2.5";
 public LinkProperties(ProxyProperties p) { proxy=p; } public ProxyProperties getHttpProxy() { return proxy; }
 public void setHttpProxy(ProxyProperties value) { proxy=value; } }
 public static class ProxyProperties { String host; int port; public ProxyProperties(String h,int p) { host=h;port=p; }
 public String getHost() { return host; } public int getPort() { return port; } }
}''',
            "android/net/wifi/WifiManager.java": '''package android.net.wifi;
import java.util.*;
public class WifiManager {
 public List<WifiConfiguration> networks=new ArrayList<WifiConfiguration>();
 public boolean denyRead,denyUpdate,saveOk=true; public int updates;
 public List<WifiConfiguration> getConfiguredNetworks() { if(denyRead) throw new SecurityException("denied");
 List<WifiConfiguration> copies=new ArrayList<WifiConfiguration>(); for(WifiConfiguration n:networks) copies.add(n.copy()); return copies; }
 public int updateNetwork(WifiConfiguration config) { if(denyUpdate) return -1;
 for(int i=0;i<networks.size();i++) if(networks.get(i).networkId==config.networkId) { networks.set(i,config.copy());updates++; return config.networkId; } return -1; }
 public boolean saveConfiguration() { return saveOk; }
}''',
            "ca/arc7freedom/app/HlsRepairHarness.java": HLS_HARNESS,
            "ca/arc7freedom/app/RuntimePolicyHarness.java": POLICY_HARNESS,
            "ca/arc7freedom/launcher/ManagementRepairHarness.java": MANAGEMENT_HARNESS,
        })
        sources = []
        for name, content in stubs.items():
            path = tmp / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            sources.append(path)
        sources += [SRC / name for name in ("LegacyMediaPolicy.java", "MorpheHls.java", "MorpheHlsRelay.java", "MorpheRemuxer.java", "MorpheStreamServer.java", "MediaNotificationPolicy.java", "LegacyWifiProxyCleanup.java")]
        sources.append(ROOT / "launcher-android4/src/main/java/ca/arc7freedom/launcher/BuiltinApps.java")
        sources += list((ROOT / "tests/support/org/json").glob("*.java"))
        classes = tmp / "classes"
        classes.mkdir()
        run([javac, "-nowarn", "-d", classes] + sources)
        for harness in ("ca.arc7freedom.app.RuntimePolicyHarness", "ca.arc7freedom.launcher.ManagementRepairHarness"):
            run([java, "-cp", classes, harness])
        output = tmp / "output"
        output.mkdir()
        run([java, "-cp", classes, "ca.arc7freedom.app.HlsRepairHarness", FIXTURES, output])
        check_compiled_binding(tmp, classes, javac, java, toolchain)
        check_upgrader_commands(tmp)
        ffmpeg = os.environ.get("FFMPEG") or shutil.which("ffmpeg")
        if ffmpeg:
            for name in ("separate.mp4", "muxed.mp4", "audio.m4a", "muxed-audio.m4a"):
                run([ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-xerror", "-i", output / name, "-f", "null", "-"])
            print("PASS: FFmpeg decoded real H.264/AAC from both classic MP4 files and both audio-only M4A files")
        else:
            print("NOTE: optional FFmpeg decoder check skipped (set FFMPEG); protocol/real-fixture remux tests passed")
    service = (SRC / "Arc7AudioService.java").read_text()
    assert "MediaNotificationPolicy.mayPost" in service
    assert "preparing.prepareAsync()" in service and "request != playbackGeneration" in service
    assert service.index("mp.start();", service.index("preparing.setOnPreparedListener")) < service.index("publishOwnPlaybackState(true);", service.index("preparing.setOnPreparedListener"))
    now_playing = (SRC / "NowPlayingManager.java").read_text()
    assert "MediaNotificationPolicy.ownsSession" in now_playing
    assert "triggerServiceNotification" not in now_playing
    main = (SRC / "NetworkCertManager.java").read_text()
    migration = main.split("public void migrateLegacySuiteProxy()", 1)[1].split("private boolean recordLegacyProxyCleanup", 1)[0]
    assert 'getBoolean("independent_networking_v1"' not in migration, "Cleanup must retry, not be permanently skipped"
    assert "LegacyWifiProxyCleanup.clear(appContext)" in main and "legacy_proxy_cleanup_pending" in main
    assert "wifi.cleared > 0" not in main and "globalAndActiveProxyAreClear(appContext)" in main
    home = (ROOT / "launcher-android4/src/main/java/ca/arc7freedom/launcher/VanillaHomeActivity.java").read_text()
    assert "openAppDetails(e.pkg)" not in home and "openAppDetails(dragged.pkg)" not in home
    assert "BuiltinApps.managementPackage(entry.pkg, entry.cls)" in home
    engine = (SRC / "MorpheEngine.java").read_text()
    assert "new int[]{0, 6, 1}" in engine and '"VISIONOS", "1.04"' in engine
    assert "MorpheHls.select" in engine and 'sd.optString("hlsManifestUrl"' in engine
    assert "customGatewayPortOverride" in engine, "Preserve the live transport test hook"
    youtube = (SRC / "YouTubeActivity.java").read_text()
    video_prepare = youtube.split("private void prepareForegroundVideo", 1)[1].split("private void failForegroundVideo", 1)[0]
    assert "request != videoRequestGeneration" in video_prepare and "currentPlayback != playback" in video_prepare
    assert video_prepare.index("videoView.start();") < video_prepare.index("setYouTubeForegroundVideoActive(true)")
    assert "125000L" in video_prepare and "no playable media was received" in video_prepare
    video_stop = youtube.split("private void stopForegroundVideo()", 1)[1].split("private void toggleFullscreenMode", 1)[0]
    assert "++videoRequestGeneration" in video_stop and "resolutionThread.interrupt()" in video_stop
    assert "MorpheEngine.cancelPlayback(currentPlayback)" in video_stop
    print("ALL RUNTIME REPAIR REGRESSIONS PASSED (host/fake Android checks, not tablet or live YouTube validation)")


if __name__ == "__main__":
    main()
