package ca.arc7freedom.app;

import android.content.Context;
import android.os.Environment;
import com.sun.net.httpserver.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Executes production algorithms/transports with synthetic providers, never the live services. */
public final class SuiteFeatureHarness {
    interface Attempt { void run() throws Exception; }
    static File work, fixtures;
    static final Map<String,byte[]> media=new ConcurrentHashMap<String,byte[]>();
    static String response="{}", lastPost="";
    static int posts;
    static void check(boolean value,String reason) { if(!value) throw new AssertionError(reason); }
    static void reject(Attempt task,String reason) throws Exception {
        try { task.run(); } catch(Exception expected) { return; }
        throw new AssertionError("Accepted "+reason);
    }
    static byte[] bytes(String s) throws Exception { return s.getBytes("UTF-8"); }
    static String hex(byte[] b) { StringBuilder out=new StringBuilder();for(byte v:b)out.append(String.format(Locale.US,"%02x",v&255));return out.toString(); }
    static String base32(String s) throws Exception {
        StringBuilder result=new StringBuilder();int n=0,bits=0;
        for(byte b:bytes(s)){n=(n<<8)|(b&255);bits+=8;while(bits>=5){bits-=5;result.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".charAt((n>>bits)&31));}}
        if(bits>0)result.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".charAt((n<<(5-bits))&31));return result.toString();
    }
    static void crypto() throws Exception {
        check(hex(Arc7VaultCrypto.pbkdf2(bytes("password"),bytes("salt"),1)).equals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"),"PBKDF2-SHA256 c=1 vector");
        check(hex(Arc7VaultCrypto.pbkdf2(bytes("password"),bytes("salt"),2)).equals("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43"),"PBKDF2-SHA256 c=2 vector");
        check(hex(Arc7VaultCrypto.pbkdf2(bytes("password"),bytes("salt"),4096)).equals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a"),"PBKDF2-SHA256 c=4096 vector");
        char[] password="A long unique master passphrase!".toCharArray();
        Arc7VaultCrypto.Session session=Arc7VaultCrypto.create(password,"passwords");
        byte[] plain=bytes("[{\"title\":\"Private account\",\"password\":\"never in cleartext on disk\"}]");
        byte[] file=Arc7VaultCrypto.seal(session,plain),second=Arc7VaultCrypto.seal(session,plain);
        check(!Arrays.equals(file,second),"fresh IV for every write");
        check(!new String(file,"ISO-8859-1").contains("Private account"),"no plaintext in encrypted file");
        Arc7VaultCrypto.Opened opened=Arc7VaultCrypto.open(file,password,"passwords");
        check(Arrays.equals(opened.plaintext,plain),"encrypted round-trip");opened.session.close();Arc7VaultCrypto.wipe(opened.plaintext);
        for(int offset:new int[]{0,15,40,file.length-33,file.length-1}) {
            byte[] bad=file.clone();bad[offset]^=1;
            reject(()->Arc7VaultCrypto.open(bad,password,"passwords"),"tampered authenticated header/IV/ciphertext/tag");
        }
        reject(()->Arc7VaultCrypto.open(file,"Wrong master password".toCharArray(),"passwords"),"wrong password");
        reject(()->Arc7VaultCrypto.open(file,password,"authenticator"),"cross-app vault");
        reject(()->Arc7VaultCrypto.open(Arrays.copyOf(file,file.length-1),password,"passwords"),"truncated vault");
        reject(()->Arc7VaultCrypto.create("short".toCharArray(),"passwords"),"short new master password");
        session.close();reject(()->Arc7VaultCrypto.seal(session,plain),"use after lock");
        File saved=new File(work,"private/vault.arc7vault");Arc7VaultCrypto.atomicWrite(saved,file);Arc7VaultCrypto.atomicWrite(saved,second);
        check(Arrays.equals(Files.readAllBytes(saved.toPath()),second),"atomic in-place save");
        File blocked=new File(work,"blocked");blocked.mkdirs();Files.write(new File(blocked,"keep").toPath(),bytes("retained"));
        reject(()->Arc7VaultCrypto.atomicWrite(blocked,file),"failed rename");check(new File(blocked,"keep").isFile(),"failed save preserves existing data");
        check(Arrays.stream(work.listFiles()).noneMatch(f->f.getName().endsWith(".tmp")),"failed temporary file removed");
        reject(()->Arc7VaultCrypto.read(new ByteArrayInputStream(new byte[Arc7VaultCrypto.MAX_BYTES+513])),"oversize backup");
        String generated=Arc7VaultCrypto.password(24,true);check(generated.length()==24&&!generated.equals(Arc7VaultCrypto.password(24,true)),"CSPRNG generator length/fresh entropy");
        Thread.currentThread().interrupt();reject(()->Arc7VaultCrypto.create(password,"passwords"),"interrupted KDF");Thread.interrupted();
        Arc7VaultCrypto.wipe(password);Arc7VaultCrypto.wipe(plain);check(password[0]==0&&plain[0]==0,"explicit secret array clearing");
        System.out.println("PASS vault: PBKDF2 vectors, random IVs/passwords, authenticated tamper/wrong-key/cross-app rejection, lock/cancel, bounds and atomic failed-save retention");
    }
    static void totp() throws Exception {
        long[] times={59,1111111109L,1111111111L,1234567890L,2000000000L,20000000000L};
        String[][] expected={{"94287082","46119246","90693936"},{"07081804","68084774","25091201"},{"14050471","67062674","99943326"},{"89005924","91819424","93441116"},{"69279037","90698825","38618901"},{"65353130","77737706","47863826"}};
        String[] secrets={"12345678901234567890","12345678901234567890123456789012","1234567890123456789012345678901234567890123456789012345678901234"};
        String[] algorithms={"SHA1","SHA256","SHA512"};
        for(int i=0;i<times.length;i++)for(int j=0;j<3;j++)check(Arc7Totp.generate(base32(secrets[j]),algorithms[j],8,30,times[i]).equals(expected[i][j]),"RFC6238 "+i+"/"+j);
        check(Arc7Totp.generate(base32(secrets[0]),"SHA-1",6,30,59).equals("287082"),"six-digit truncation");
        Arc7Totp.Account account=Arc7Totp.parse("otpauth://totp/Issuer:a%2Bb?secret=JBSWY3DPEHPK3PXP&issuer=Issuer&algorithm=SHA256&digits=8&period=60");
        check(account.label.equals("a+b")&&account.issuer.equals("Issuer")&&account.period==60&&account.digits==8&&account.algorithm.equals("SHA256"),"otpauth parsing/encoding/options");
        for(String s:new String[]{"ABC","JBSWY3DPEHPK3PX!","JBSWY3DPEHPK3PXP=","JBSWY3DPEHPK3PXPAB","otpauth://hotp/test?secret=JBSWY3DPEHPK3PXP","otpauth://totp/Issuer:user?issuer=Other&secret=JBSWY3DPEHPK3PXP","otpauth://totp/user?secret=JBSWY3DPEHPK3PXP&secret=JBSWY3DPEHPK3PXP","otpauth://totp/user?secret=JBSWY3DPEHPK3PXP&digits=7"})reject(()->Arc7Totp.parse(s),"malformed Base32/otpauth");
        reject(()->Arc7Totp.generate(base32(secrets[0]),"MD5",6,30,59),"unsupported algorithm");
        reject(()->Arc7Totp.generate(base32(secrets[0]),"SHA1",6,1,59),"unsafe period");
        System.out.println("PASS authenticator: all 18 RFC6238 SHA1/256/512 vectors, 6/8 digits, encoded URI/issuer/period and malformed/duplicate/HOTP rejection");
    }
    static void keys() throws Exception {
        check(Arc7KeyBinding.normalize(" shift-control-a ").equals("CTRL+SHIFT+A"),"keybind canonical order");
        check(Arc7KeyBinding.normalize("CMD+RETURN").equals("WIN+ENTER"),"key aliases");
        for(String key:Arc7KeyBinding.KEYS)check(Arc7KeyBinding.normalize(key).equals(key),"key picker "+key);
        for(String bad:new String[]{"","CTRL","CTRL+CTRL+A","A+B","F25","CTRL++A","CTRL+NO_SUCH_KEY","+A","A+"})reject(()->Arc7KeyBinding.normalize(bad),"bad keybind "+bad);
        System.out.println("PASS Android key grammar: every picker key, modifier ordering/aliases and no silent invalid-key fallback");
    }
    static void codecs() throws Exception {
        check(LegacyMediaPolicy.aacLc("audio/mp4; codecs=\"mp4a.40.2\"",2,48000),"AAC LC");
        for(String codec:new String[]{"mp4a.40.5","mp4a.40.29","mp4a.40.20","opus",""})check(!LegacyMediaPolicy.aacLc(codec,2,44100),"unsupported "+codec);
        check(!LegacyMediaPolicy.aacLc("mp4a.40.2",6,44100)&&!LegacyMediaPolicy.aacLc("mp4a.40.2",2,96000),"channel/sample limits");
        check(LegacyMediaPolicy.h264("avc1.42C01E",720,30)&&!LegacyMediaPolicy.h264("avc1",1080,30)&&!LegacyMediaPolicy.h264("avc1",720,60),"legacy video caps");
        String prefix="https://playerservices.streamtheworld.com/api/livestream-redirect/";
        check(LegacyMediaPolicy.canonicalRadio(prefix+"CBLAFM2.mp3").equals(prefix+"CBLAFM2_CBC.mp3"),"Waterloo regional URL preserved");
        check(LegacyMediaPolicy.radioFallback(prefix+"CBLAFM2.mp3").isEmpty()&&!LegacyMediaPolicy.radioFallback(prefix+"CBLAFM.mp3").isEmpty(),"Toronto fallback only");
        check(LegacyMediaPolicy.error(1,1004).contains("1/1004")&&LegacyMediaPolicy.error(1,-1004).contains("1/-1004")&&LegacyMediaPolicy.error(1,-1010).contains("Unsupported"),"literal positive and negative errors not confused");
        JSONObject root=new JSONObject("{\"videoDetails\":{\"videoId\":\"fixture0001\",\"title\":\"Video\"},\"streamingData\":{\"formats\":[],\"adaptiveFormats\":[]}}");
        JSONArray combined=root.getJSONObject("streamingData").getJSONArray("formats");
        combined.put(format("video/mp4; codecs=\"avc1.42c01e, mp4a.40.2\"",720,30,128000));
        combined.put(format("video/mp4; codecs=\"avc1.42c01e\"",360,30,128000));
        combined.put(format("video/mp4; codecs=\"vp09, opus\"",360,30,128000));
        JSONArray adaptive=root.getJSONObject("streamingData").getJSONArray("adaptiveFormats");
        adaptive.put(format("audio/mp4; codecs=\"mp4a.40.2\"",0,0,256000));
        adaptive.put(format("audio/mp4; codecs=\"mp4a.40.2\"",0,0,128000));
        adaptive.put(format("audio/mp4; codecs=\"mp4a.40.5\"",0,0,48000));
        adaptive.put(format("audio/mp4; codecs=\"mp4a.40.2\"",0,0,192000).put("audioSampleRate","96000"));
        adaptive.put(format("video/mp4; codecs=\"avc1.42c01e\"",720,60,500000));
        MorpheEngine.ResolvedPlayback parsed=MorpheEngine.parseInnerTubePlayerResponse(root,"fixture0001","fixture","fixture-UA");
        check(parsed.progressiveVideos.size()==1&&parsed.progressiveVideos.get(0).hasAudio,"no silent progressive video");
        check(parsed.audioStreams.size()==2&&parsed.audioStreams.get(0).bitrate==128000&&parsed.videoOnlyStreams.isEmpty(),"AAC-LC selection/order and no 60fps");
        check(MorpheEngine.parseInnerTubePlayerResponse(root,"different00","fixture","fixture-UA")==null,"placeholder identity rejected");
        System.out.println("PASS actual player parser: combined real audio/video, codec/channel/rate caps, AAC128 preference, video identity and CBC URL/error policy");
    }
    static JSONObject format(String mime,int height,int fps,int bitrate) {
        return new JSONObject().put("mimeType",mime).put("url","https://fixture.test/track").put("height",height).put("fps",fps).put("bitrate",bitrate).put("audioChannels",2).put("audioSampleRate","44100");
    }
    @SuppressWarnings("unchecked")
    static List<MorpheEngine.MediaItem> music(JSONObject root) throws Exception {
        Method parser=MorpheEngine.class.getDeclaredMethod("parseYouTubeItemsRecursive",JSONObject.class,boolean.class);parser.setAccessible(true);
        return (List<MorpheEngine.MediaItem>)parser.invoke(null,root,true);
    }
    static void collections(Context c) throws Exception {
        JSONObject album=new JSONObject("{\"musicTwoRowItemRenderer\":{\"title\":{\"runs\":[{\"text\":\"Album\"}]},\"navigationEndpoint\":{\"browseEndpoint\":{\"browseId\":\"MPREb_album\"}}}}");
        JSONObject playlist=new JSONObject("{\"musicTwoRowItemRenderer\":{\"title\":{\"runs\":[{\"text\":\"Community playlist\"}]},\"navigationEndpoint\":{\"browseEndpoint\":{\"browseId\":\"VLPL_example\"}}}}");
        JSONObject song=new JSONObject("{\"musicResponsiveListItemRenderer\":{\"playlistItemData\":{\"videoId\":\"fixture0001\"},\"flexColumns\":[{\"musicResponsiveListItemFlexColumnRenderer\":{\"text\":{\"runs\":[{\"text\":\"Song\"}]}}}]}}");
        List<MorpheEngine.MediaItem> parsed=music(new JSONObject().put("contents",new JSONArray().put(album).put(playlist).put(song)));
        check(parsed.size()==3&&parsed.get(0).isCollection()&&parsed.get(1).playlistId.equals("PL_example")&&!parsed.get(2).isCollection(),"albums/playlists are not fake songs");
        MorpheEngine.addOrToggleSavedItem(c,"collections",parsed.get(0),false);MorpheEngine.addOrToggleSavedItem(c,"collections",parsed.get(1),false);
        MorpheEngine.MediaItem alias=new MorpheEngine.MediaItem();alias.collectionType="playlist";alias.playlistId="PL_example";alias.title="Same playlist";
        MorpheEngine.addOrToggleSavedItem(c,"collections",alias,false);
        check(MorpheEngine.loadSavedList(c,"collections").size()==2,"playlist browse/watch alias identity de-duplicated");
        MorpheEngine.addOrToggleSavedItem(c,"collections",alias,true);check(MorpheEngine.loadSavedList(c,"collections").size()==1,"remove only selected collection");
        MorpheEngine.MediaItem local=new MorpheEngine.MediaItem();local.collectionType="playlist";local.playlistId="local:fixture-list";
        MorpheEngine.saveList(c,"ytm_local_fixture-list",Arrays.asList(parsed.get(2)));
        check(MorpheEngine.browseMusicCollection(c,local).get(0).videoId.equals("fixture0001"),"independent local playlist contents");
        MorpheEngine.MediaItem second=new MorpheEngine.MediaItem();second.videoId="fixture0002";
        MorpheEngine.addOrToggleSavedItem(c,"ytm_local_fixture-list",second,false);
        List<MorpheEngine.MediaItem> ordered=MorpheEngine.browseMusicCollection(c,local);
        check(ordered.size()==2&&ordered.get(0).videoId.equals("fixture0001")&&ordered.get(1).videoId.equals("fixture0002"),"local additions preserve playlist order");
        response=new JSONObject().put("contents",new JSONObject().put("singleColumnBrowseResultsRenderer",new JSONObject().put("musicPlaylistShelfRenderer",new JSONObject().put("contents",new JSONArray().put(song))))).toString();
        List<MorpheEngine.MediaItem> tracks=MorpheEngine.browseMusicCollection(c,parsed.get(1));
        check(tracks.size()==1&&tracks.get(0).videoId.equals("fixture0001"),"public collection browse");
        response="{\"contents\":{\"musicCarouselShelfRenderer\":{\"contents\":["+song+"]}}}";
        reject(()->MorpheEngine.browseMusicCollection(c,parsed.get(1)),"recommendations substituted for collection tracks");
        int before=posts;MorpheEngine.searchYouTubeMusic(c,"fixture","community_playlists");
        check(posts==before+1&&lastPost.contains("EgeKAQQoAEABagwQDhAKEAMQBBAJEAU"),"community filter requests its own endpoint once");
        System.out.println("PASS Music collections: real album/playlist identities, persistent aliases/removal, local playlists, selected collection tracks, no recommendation substitution and community search filter");
    }
    static void pins(Context c) throws Exception {
        String a="UCAAAAAAAAAAAAAAAAAAAAAA",b="UCBBBBBBBBBBBBBBBBBBBBBB";
        MorpheEngine.MediaItem one=MorpheEngine.resolvePinnedChannel(c,a,"One"),two=MorpheEngine.resolvePinnedChannel(c,"https://www.youtube.com/channel/"+b,"Two");
        MorpheEngine.addOrToggleSavedItem(c,"pins",one,false);MorpheEngine.addOrToggleSavedItem(c,"pins",two,false);MorpheEngine.addOrToggleSavedItem(c,"pins",one,false);
        check(MorpheEngine.loadSavedList(c,"pins").size()==2,"distinct channel persistence and dedup");MorpheEngine.addOrToggleSavedItem(c,"pins",one,true);check(MorpheEngine.loadSavedList(c,"pins").get(0).channelId.equals(b),"local unpin");
        int before=posts;reject(()->MorpheEngine.resolvePinnedChannel(c,"https://evil.invalid/channel/"+a,"bad"),"untrusted channel URL");check(posts==before,"invalid URL never fetched");
        response="{\"endpoint\":{\"browseEndpoint\":{\"browseId\":\""+a+"\"}}}";
        check(MorpheEngine.resolvePinnedChannel(c,"@example","Handle").channelId.equals(a),"public handle resolution");
        String xml="<feed xmlns='http://www.w3.org/2005/Atom' xmlns:yt='http://www.youtube.com/xml/schemas/2015'><entry><yt:videoId>fixture0001</yt:videoId><title>Title &amp; details</title><author><name>Channel</name></author></entry><entry><yt:videoId>invalid</yt:videoId></entry></feed>";
        List<MorpheEngine.MediaItem> videos=MorpheEngine.parsePinnedChannelFeed(xml,a);
        check(videos.size()==1&&videos.get(0).channelId.equals(a)&&videos.get(0).title.equals("Title & details"),"Atom namespaces/metadata/invalid video filtering");
        reject(()->MorpheEngine.parsePinnedChannelFeed("<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"+xml,a),"external entity declaration");
        reject(()->MorpheEngine.parsePinnedChannelFeed(new String(new char[1024*1024+1]),a),"oversize channel XML");
        System.out.println("PASS YouTube local pins: channel/handle validation, persistence/dedup/unpin, real Atom namespaces and bounded XXE-rejecting public feeds");
    }
    static void ranges() throws Exception {
        byte[] full=new byte[256];for(int i=0;i<full.length;i++)full[i]=(byte)i;media.put("range",full);
        check(Arrays.equals(MorpheEngine.fetchRangeThroughGateway(null,"https://fixture.test/range","test",16,63),Arrays.copyOfRange(full,16,64)),"valid inclusive range");
        check(Arrays.equals(MorpheEngine.fetchRangeThroughGateway(null,"https://fixture.test/whole","test",16,63),Arrays.copyOfRange(full,16,64)),"200 fallback skips exact prefix");
        for(String name:new String[]{"wrong-offset","missing-range","short-range","short-whole"})reject(()->MorpheEngine.fetchRangeThroughGateway(null,"https://fixture.test/"+name,"test",0,63),"invalid/truncated "+name);
        check(MorpheEngine.fetchRangeThroughGateway(null,"https://fixture.test/range","test",256,300).length==0,"confirmed EOF 416");
        reject(()->MorpheEngine.fetchRangeThroughGateway(null,"https://fixture.test/range","test",1,0),"negative range length");
        System.out.println("PASS real HTTP media ranges: exact offsets/declared lengths, 200 skip, genuine EOF and short/wrong/missing range rejection");
    }
    static void downloads(Context c) throws Exception {
        MorpheRemuxer.setCacheDir(new File(work,"spool"));new File(work,"spool").mkdirs();
        MorpheEngine.ResolvedPlayback hls=new MorpheEngine.ResolvedPlayback();hls.hlsManifestUrl="https://fixture.test/media/muxed.m3u8";hls.hlsUserAgent="test";
        final List<Integer> progress=new ArrayList<Integer>();
        File video=MorpheEngine.downloadResolvedOffline(c,hls,"X",false,(p,d,t)->progress.add(p));MorpheRemuxer.validateClassicFile(video,false);
        check(progress.get(progress.size()-1)==100&&video.getName().endsWith(".mp4"),"finite HLS produces a complete MP4");
        File audio=MorpheEngine.downloadResolvedOffline(c,hls,"X",true,null);MorpheRemuxer.validateClassicFile(audio,true);
        media.put("progressive",Files.readAllBytes(video.toPath()));
        MorpheEngine.StreamVariant combined=new MorpheEngine.StreamVariant();combined.hasAudio=true;combined.hasVideo=true;combined.mimeType="video/mp4";combined.directHttpsUrl="https://fixture.test/progressive";combined.contentLength=video.length();
        MorpheEngine.ResolvedPlayback progressive=new MorpheEngine.ResolvedPlayback();progressive.progressiveVideos.add(combined);
        File copy=MorpheEngine.downloadResolvedOffline(c,progressive,"X",false,null);check(Arrays.equals(Files.readAllBytes(copy.toPath()),Files.readAllBytes(video.toPath()))&&!copy.equals(video),"progressive initEnd=-1, unique files, no overwrite");
        combined.contentLength+=5;reject(()->MorpheEngine.downloadResolvedOffline(c,progressive,"Truncated",false,null),"short progressive file");combined.contentLength-=5;
        combined.hasAudio=false;reject(()->MorpheEngine.downloadResolvedOffline(c,progressive,"Silent",false,null),"video-only file labelled complete MP4");combined.hasAudio=true;
        Thread.currentThread().interrupt();reject(()->MorpheEngine.downloadResolvedOffline(c,progressive,"Cancelled",false,null),"interrupted download");Thread.interrupted();
        File dir=video.getParentFile();check(Arrays.stream(dir.listFiles()).noneMatch(f->f.getName().endsWith(".part")),"failed/cancelled downloads remove all partial files");
        Files.copy(video.toPath(),new File(work,"validated-video.mp4").toPath());Files.copy(audio.toPath(),new File(work,"validated-audio.m4a").toPath());
        System.out.println("PASS actual downloader: finite HLS -> real MP4/M4A, progressive -1 init range, short one-letter titles, unique atomic completion, no silent-video fallback, truncation/cancel cleanup");
    }
    static Thread worker(Arc7DownloadUi downloads) throws Exception {
        Field field=Arc7DownloadUi.class.getDeclaredField("worker");field.setAccessible(true);return (Thread)field.get(downloads);
    }
    static void finishUi(Arc7DownloadUi downloads) throws Exception {
        Thread thread=worker(downloads);if(thread!=null){thread.join(3000);check(!thread.isAlive(),"download worker ended");}
        android.os.Handler.drain();check(worker(downloads)==null,"download completion cleared worker ownership");
    }
    static void downloadUi() throws Exception {
        android.app.Activity host=new android.app.Activity();Arc7DownloadUi downloads=new Arc7DownloadUi(host);
        CountDownLatch started=new CountDownLatch(1),hold=new CountDownLatch(1),cancelled=new CountDownLatch(1),cleanup=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger jobs=new java.util.concurrent.atomic.AtomicInteger();
        downloads.start("Cancel fixture",progress->{
            jobs.incrementAndGet();progress.onProgress(25,1,4);started.countDown();
            try {hold.await();}catch(InterruptedException expected){cancelled.countDown();cleanup.await(3,TimeUnit.SECONDS);throw new InterruptedIOException("cancelled");}
            throw new IOException("fixture should not complete");
        });
        check(started.await(2,TimeUnit.SECONDS),"visible download started");android.os.Handler.drain();
        android.app.ProgressDialog dialog=android.app.ProgressDialog.latest;
        check(dialog.showing&&!dialog.indeterminate&&dialog.progress==25&&dialog.max==100,"real progress UI reflects worker updates");
        downloads.start("No overlap",progress->{jobs.incrementAndGet();return null;});check(jobs.get()==1,"one job per Activity");
        dialog.cancel();check(cancelled.await(2,TimeUnit.SECONDS),"UI cancel interrupts its worker");
        downloads.start("Wait for cleanup",progress->{jobs.incrementAndGet();return null;});check(jobs.get()==1,"cancelled worker owns slot until cleanup");
        cleanup.countDown();finishUi(downloads);check(!dialog.showing,"cancelled dialog dismissed");
        File completed=new File(work,"ui-completed.mp4");
        downloads.start("Success fixture",progress->{Files.write(completed.toPath(),new byte[]{1,2,3});return completed;});finishUi(downloads);
        check(completed.isFile()&&android.widget.Toast.messages.get(android.widget.Toast.messages.size()-1).startsWith("Saved offline:"),"only completed output reported saved");
        downloads.start("Failure fixture",progress->{throw new IOException("fixture failed");});finishUi(downloads);
        check(android.widget.Toast.messages.get(android.widget.Toast.messages.size()-1).contains("Download failed: fixture failed"),"failure visible, never fabricated success");
        CountDownLatch closing=new CountDownLatch(1);
        downloads.start("Destroyed Activity",progress->{closing.countDown();new CountDownLatch(1).await();return null;});
        check(closing.await(2,TimeUnit.SECONDS),"destroy fixture running");int messages=android.widget.Toast.messages.size();
        downloads.close();finishUi(downloads);check(!android.app.ProgressDialog.latest.showing&&messages==android.widget.Toast.messages.size(),"destroy cancels/dismisses with no late Activity UI");
        Arc7DownloadUi racing=new Arc7DownloadUi(host);File kept=new File(work,"ui-keep.mp4");
        racing.start("Completed before destroy",progress->{Files.write(kept.toPath(),new byte[]{4});return kept;});
        Thread thread=worker(racing);thread.join(3000);check(!thread.isAlive(),"completion race finished");
        racing.close();finishUi(racing);check(kept.isFile(),"late cancel keeps a completed file");
        racing.start("Closed helper",progress->{jobs.incrementAndGet();return null;});check(worker(racing)==null&&jobs.get()==1,"closed Activity cannot start another download");
        System.out.println("PASS real offline download UI: progress, overlap/cleanup gate, cancel/destroy interrupts, honest success/errors and completed-file preservation (Android UI fakes)");
    }
    static void serve(HttpExchange exchange) throws IOException {
        try {
            if(exchange.getRequestMethod().equals("POST")) {
                posts++;lastPost=new String(exchange.getRequestBody().readAllBytes(),"UTF-8");byte[] body=response.getBytes("UTF-8");
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);return;
            }
            String path=exchange.getRequestURI().getPath(),name=path.substring(path.lastIndexOf('/')+1);
            byte[] body=media.get(name);
            if(path.startsWith("/__https/fixture.test/media/"))body=Files.readAllBytes(new File(fixtures,name).toPath());
            if(body==null)body=media.get("range");
            if(body==null){exchange.sendResponseHeaders(404,-1);return;}
            String range=exchange.getRequestHeaders().getFirst("Range");int status=200;long length=body.length;
            if(range!=null&&!name.equals("whole")&&!name.equals("short-whole")) {
                String[] values=range.substring(6).split("-");int start=Integer.parseInt(values[0]),end=Math.min(body.length-1,Integer.parseInt(values[1]));
                if(start>=body.length){exchange.getResponseHeaders().add("Content-Range","bytes */"+body.length);exchange.sendResponseHeaders(416,-1);return;}
                if(!name.equals("missing-range"))exchange.getResponseHeaders().add("Content-Range","bytes "+(name.equals("wrong-offset")?start+1:start)+"-"+end+"/"+body.length);
                body=Arrays.copyOfRange(body,start,end+1);length=body.length;status=206;
            }
            exchange.sendResponseHeaders(status,length);
            if(name.startsWith("short-"))body=Arrays.copyOf(body,Math.min(7,body.length));
            exchange.getResponseBody().write(body);
        } catch(Exception ignored) { /* intentional disconnect / truncated response fixtures */ }
        finally { exchange.close(); }
    }
    public static void main(String[] args) throws Exception {
        work=new File(args[0]);fixtures=new File(args[1]);work.mkdirs();Environment.root=new File(work,"sdcard");Environment.root.mkdirs();
        Context c=new Context();c.cache=work;
        crypto();totp();keys();codecs();downloadUi();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",SuiteFeatureHarness::serve);
        server.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;}));server.start();
        try {MorpheEngine.setGatewayPortForTest(server.getAddress().getPort());collections(c);pins(c);ranges();downloads(c);}
        finally {server.stop(0);}
    }
}
