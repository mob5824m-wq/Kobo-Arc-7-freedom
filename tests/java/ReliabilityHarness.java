package ca.arc7freedom.app;

import ca.arc7freedom.support.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

/** Production Java + real host filesystem/HTTP. Not Android, physical storage, speaker or LAN proof. */
public final class ReliabilityHarness {
    private static File work, assets;
    private interface Action { void run() throws Exception; }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void rejects(Action action,String text)throws Exception{try{action.run();}catch(IOException expected){return;}throw new AssertionError("Accepted "+text);}
    private static void status(Action action,String code)throws Exception{try{action.run();}catch(Arc7ResumableUploads.Failure expected){check(expected.status.startsWith(code),"Wrong status "+expected.status+" expected "+code);return;}throw new AssertionError("Expected "+code);}
    private static String sha(byte[] data)throws Exception {return hex(MessageDigest.getInstance("SHA-256").digest(data));}
    private static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format(Locale.US,"%02x",v&255));return b.toString();}
    private static File folder(File parent,String name)throws Exception{File f=new File(parent,name);check(f.mkdirs()||f.isDirectory(),"mkdir "+name);return f;}
    private static byte[] bytes(String text)throws Exception{return text.getBytes("UTF-8");}
    private static String text(File file)throws Exception{return new String(Files.readAllBytes(file.toPath()),"US-ASCII");}
    private static void logging()throws Exception {
        File one=folder(work,"log-one"),two=folder(work,"log-two");DebugLog.initialize(one,"ca.arc7freedom.notes",true);
        String secret="secret-password-otp123 https://private.invalid/path?token=raw-token";
        Exception error=new IOException(secret,new Exception(secret));error.setStackTrace(new StackTraceElement[]{new StackTraceElement("ca.arc7freedom.SafeClass","method",secret,42)});
        DebugLog.event(secret);DebugLog.lifecycle("activity.resume",secret);DebugLog.error("fixture.failed",error);
        String log=DebugLog.snapshot();check(!log.contains("secret-password")&&!log.contains("private.invalid")&&!log.contains("raw-token")&&log.contains("java.io.IOException")&&log.contains("method line=42"),"Exception messages/causes/filenames/user text leaked");
        long count=new File(one,"diagnostics/events.log").length();DebugLog.setEnabled(false);DebugLog.event("must.not.persist");check(count==new File(one,"diagnostics/events.log").length(),"Disabled log changed");
        DebugLog.setEnabled(true);
        final AtomicInteger delegated=new AtomicInteger();Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,failure)->delegated.incrementAndGet());DebugLog.installCrashHandler();Thread.getDefaultUncaughtExceptionHandler().uncaughtException(new Thread(secret),error);check(delegated.get()==1,"Crash handler didn't delegate");Thread.setDefaultUncaughtExceptionHandler(previous);
        for(int i=0;i<11000;i++)DebugLog.number("fixture.rotation.counter",i);
        Thread[] writers=new Thread[4];for(int i=0;i<writers.length;i++){writers[i]=new Thread(()->{for(int j=0;j<100;j++)DebugLog.number("fixture.concurrent",j);});writers[i].start();}for(Thread t:writers)t.join();
        check(Files.getPosixFilePermissions(new File(one,"diagnostics").toPath()).stream().noneMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")),"Log directory not owner-private");
        File[] logs=new File(one,"diagnostics").listFiles();check(logs!=null&&logs.length==3,"Expected exactly three rotated logs");
        long total=0;for(File f:logs){check(f.length()<=DebugLog.FILE_BYTES,"Unbounded log segment");total+=f.length();for(String line:text(f).split("\n"))check(line.matches("[0-9]+ (event|frame|process)[^\\r]*"),"Torn structured log line");}check(total<=384*1024,"Log quota exceeded");
        check(DebugLog.snapshot().getBytes("US-ASCII").length<=DebugLog.EXPORT_BYTES,"Export not bounded");
        DebugLog.initialize(two,"ca.arc7freedom.calculator",true);check(!DebugLog.snapshot().contains("fixture.concurrent"),"Cross-app log mixing");
        DebugLog.clear();check(new File(two,"diagnostics").listFiles().length==0,"Clear didn't remove only local rotations");
        File linked=folder(work,"log-linked"),outside=folder(work,"log-outside");Files.createSymbolicLink(new File(linked,"diagnostics").toPath(),outside.toPath());DebugLog.initialize(linked,"ca.arc7freedom.test",true);DebugLog.event("fixture.symlink");check(outside.listFiles().length==0,"Symlink log directory followed");
        DebugLog.initialize(one,"ca.arc7freedom.filemanager",true);
        System.out.println("PASS bounded per-owner logs: rotation/concurrency, 64 KiB snapshot, enable/clear, secret-bearing exception/cause/source/thread omission, crash delegation, symlink refusal and independent private roots");
    }
    private static void cacheAndProfile()throws Exception {
        File root=folder(work,"cache"),saved=new File(work,"saved-download.m4a"),vault=new File(work,"vault.enc"),resume=new File(work,"receive.part");
        Files.write(saved.toPath(),bytes("saved"));Files.write(vault.toPath(),bytes("private"));Files.write(resume.toPath(),bytes("resume"));
        MediaCache.initialize(root,32L*1048576);File managed=MediaCache.directory();
        File old=new File(managed,"old.mp4"),newer=new File(managed,"new.mp4");Files.write(old.toPath(),new byte[1024]);Files.write(newer.toPath(),new byte[2048]);old.setLastModified(1000L);newer.setLastModified(2000L);
        check(MediaCache.size()==3072,"Cache byte total incorrect");
        MediaCache.Lease held=MediaCache.lease();rejects(()->MediaCache.trim(0),"clear active media");check(old.exists()&&newer.exists(),"Active cache was touched");held.close();held.close();check(!MediaCache.busy(),"Lease close not idempotent");
        check(MediaCache.trim(2048)==1024&&!old.exists()&&newer.exists(),"Oldest-first trim failed");
        rejects(()->MediaCache.reserve(33L*1048576),"over-limit media reservation");
        File large=new File(managed,"sparse.mp4");try(RandomAccessFile f=new RandomAccessFile(large,"rw")){f.setLength(33L*1048576);}rejects(()->MediaCache.checkBudget(),"over-budget remux");MediaCache.trim(0);
        check(saved.exists()&&vault.exists()&&resume.exists(),"Cache cleanup deleted saved/private/resume data");
        MediaCache.setLimit(64L*1048576);MediaCache.Lease active=MediaCache.lease();
        try(RandomAccessFile f=new RandomAccessFile(large,"rw")){f.setLength(40L*1048576);}MediaCache.setLimit(32L*1048576);
        MediaCache.checkBudget();check(MediaCache.activeLimit()==64L*1048576,"Lower limit interrupted active jobs");rejects(()->MediaCache.reserve(1),"new reservation under lowered limit");active.close();rejects(()->MediaCache.checkBudget(),"idle budget not lowered");MediaCache.trim(0);
        File link=new File(managed,"external");Files.createSymbolicLink(link.toPath(),saved.toPath());rejects(()->MediaCache.trim(0),"symlink cache entry");check(saved.isFile(),"Symlink cleanup changed saved data");Files.delete(link.toPath());
        StorageUsage.Result usage=StorageUsage.measure(managed);check(usage.bytes==0,"Read-only size mismatch");
        Files.createSymbolicLink(link.toPath(),saved.toPath());usage=StorageUsage.measure(managed);check(usage.partial&&usage.bytes==0&&saved.exists(),"Storage scan followed a symlink");Files.delete(link.toPath());
        RuntimePolicy.setLowRam(true);check(RuntimePolicy.artworkEdge()==128&&RuntimePolicy.thumbnailEntries()==8&&RuntimePolicy.wallpaperEdge()==96&&RuntimePolicy.mediaJobs()==1,"Low-RAM limits differ");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger running=new AtomicInteger(),maximum=new AtomicInteger();
        Runnable task=()->{int n=running.incrementAndGet();maximum.updateAndGet(v->Math.max(v,n));entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{running.decrementAndGet();}};
        check(RuntimePolicy.artwork(task)&&entered.await(2,TimeUnit.SECONDS),"Artwork worker not started");int rejected=0;for(int i=0;i<50;i++)if(!RuntimePolicy.artwork(task))rejected++;
        check(rejected>=26&&maximum.get()==1,"Artwork queue/worker unbounded or work ran synchronously");RuntimePolicy.trimMemory();
        check(RuntimePolicy.artwork("fixture-art",task)&&!RuntimePolicy.artwork("fixture-art",task),"Duplicate artwork request was not coalesced");
        RuntimePolicy.trimMemory();check(RuntimePolicy.artwork("fixture-art",task),"Dropping queued artwork poisoned future retries");
        RuntimePolicy.trimMemory();release.countDown();RuntimePolicy.setLowRam(false);
        check(RuntimePolicy.artworkEdge()==256&&RuntimePolicy.thumbnailEntries()==32&&RuntimePolicy.mediaJobs()==3,"Normal profile not restored");
        System.out.println("PASS managed cache/profile: real sizes, leases block active cleanup, idempotent close, oldest-first limits, budget refusal, saved/vault/resume isolation, symlink refusal, read-only accounting and bounded low-RAM artwork queue");
    }
    private static void mediaJobs() throws Exception {
        RuntimePolicy.setLowRam(true);MorpheStreamServer.setCacheDir(MediaCache.directory());
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),ended=new CountDownLatch(1);
        String first=MorpheStreamServer.register(0,(out,progress)->{try{out.write(new byte[256]);progress.onBytes(256,256);entered.countDown();release.await(3,TimeUnit.SECONDS);}finally{ended.countDown();}},256,"audio/mp4");
        check(first.length()>0&&entered.await(2,TimeUnit.SECONDS),"Low-RAM media job did not start");
        AtomicInteger extra=new AtomicInteger();String second=MorpheStreamServer.register(0,(out,progress)->extra.incrementAndGet(),1,"audio/mp4");
        check(second.length()==0&&extra.get()==0,"Low-RAM admitted overlapping media preparation");rejects(()->MediaCache.trim(0),"cache cleanup under real media worker");
        release.countDown();check(ended.await(2,TimeUnit.SECONDS),"Media worker failed to finish");
        MorpheStreamServer.cancel(first);long deadline=System.currentTimeMillis()+2000;while(MediaCache.busy()&&System.currentTimeMillis()<deadline)Thread.sleep(5);
        check(!MediaCache.busy(),"Completed/cancelled media job leaked a cache lease");MediaCache.trim(0);RuntimePolicy.setLowRam(false);
        System.out.println("PASS production media proxy: low-RAM single-job admission, no queued hidden work, active cache cleanup refusal and completed/cancelled worker lease release");
    }
    private static void mediaBudgets(File fixtures)throws Exception {
        File managed=MediaCache.directory();MediaCache.setLimit(32L*1048576);MediaCache.trim(0);
        MorpheRemuxer.setCacheDir(managed);
        File occupied=new File(managed,"occupied.bin"),output=new File(managed,"budget-output.m4a");
        byte[] init=Files.readAllBytes(new File(fixtures,"audio-init.bin").toPath()),fragment=Files.readAllBytes(new File(fixtures,"audio-fragment.bin").toPath());
        byte[] track=new byte[init.length+fragment.length];System.arraycopy(init,0,track,0,init.length);System.arraycopy(fragment,0,track,init.length,fragment.length);
        try(RandomAccessFile f=new RandomAccessFile(occupied,"rw")){f.setLength(32L*1048576-160000);}
        MediaCache.Lease held=MediaCache.lease();
        try(RandomAccessFile out=new RandomAccessFile(output,"rw")) {
            MorpheRemuxer.Track input=new MorpheRemuxer.Track("audio/mp4",track.length,init.length-1,1200,(start,end)->Arrays.copyOfRange(track,(int)start,(int)end+1));
            rejects(()->MorpheRemuxer.copyTrack(input,out,null),"combined spool + final-output budget");
            check(out.length()>0&&out.length()<102400,"Budget was not checked during classic output copy while its spool still existed");
        }finally{held.close();MediaCache.trim(0);}
        try(RandomAccessFile f=new RandomAccessFile(occupied,"rw")){f.setLength(32L*1048576-524288);}
        held=MediaCache.lease();final AtomicInteger fetched=new AtomicInteger();
        try(RandomAccessFile out=new RandomAccessFile(output,"rw")) {
            MorpheRemuxer.Track input=new MorpheRemuxer.Track("video/mp4",-1,-1,0,(start,end)->{fetched.incrementAndGet();return start>=4L*1048576?new byte[0]:new byte[(int)(end-start+1)];});
            rejects(()->MorpheRemuxer.copyTrack(input,out,null),"unknown-length raw-output budget");
            check(fetched.get()<=2&&out.length()<=1048576,"Unknown output was allowed to fill the disk before budget refusal");
        }finally{held.close();MediaCache.trim(0);}
        System.out.println("PASS production remux budgets: final classic output is counted while its input spool still exists; unknown-length raw copy stops on bounded range writes instead of filling the disk");
    }
    private static Arc7ResumableUploads store(File root,File journal)throws Exception{return new Arc7ResumableUploads(root,journal);}
    private static void phase(File journal,String id,int phase,long updated)throws Exception {
        File file=new File(journal,id+".state");byte[] raw=Files.readAllBytes(file.toPath());
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(raw));DataOutputStream out=new DataOutputStream(new FileOutputStream(file))){
            out.writeInt(in.readInt());for(int i=0;i<5;i++)out.writeUTF(in.readUTF());out.writeLong(in.readLong());out.writeLong(in.readLong());long time=in.readLong();out.writeLong(updated<0?time:updated);in.readInt();out.writeInt(phase);out.writeUTF(in.readUTF());
        }
    }
    private static void resumable()throws Exception {
        File root=folder(work,"resume-root"),journals=folder(work,"private-journals"),foreign=folder(work,"foreign-root");Arc7ResumableUploads s=store(root,journals);
        byte[] data=new byte[2*1048576+123];new Random(7).nextBytes(data);String checksum=sha(data);
        Arc7ResumableUploads.Result r=s.begin(root,"large.bin",data.length,checksum);String id=r.id;
        final Arc7ResumableUploads initial=s;
        status(()->initial.finish(id,null),"409");check(!new File(root,"large.bin").exists(),"Incomplete bytes were published");
        byte[] first=Arrays.copyOfRange(data,0,1048576),second=Arrays.copyOfRange(data,1048576,2097152),last=Arrays.copyOfRange(data,2097152,data.length);
        r=s.chunk(id,0,first.length,sha(first),new ByteArrayInputStream(first),null);check(r.offset==first.length,"Chunk ack incorrect");
        status(()->initial.chunk(id,0,first.length,sha(first),new ByteArrayInputStream(first),null),"409");
        status(()->initial.chunk(id,first.length,second.length,sha(first),new ByteArrayInputStream(second),null),"422");
        rejects(()->initial.chunk(id,first.length,second.length,sha(second),new ByteArrayInputStream(Arrays.copyOf(second,3333)),null),"truncated chunk");
        check(initial.status(id).offset==first.length&&new File(root,".arc7-share-"+id+".part").length()==first.length,"Interrupted/corrupt chunk advanced durable offset");
        try(RandomAccessFile part=new RandomAccessFile(new File(root,".arc7-share-"+id+".part"),"rw")){part.seek(part.length());part.write(new byte[123]);}
        s=store(root,journals);check(s.begin(root,"large.bin",data.length,checksum).id.equals(id)&&s.status(id).offset==first.length,"Restart lost identity/offset");
        check(new File(root,".arc7-share-"+id+".part").length()==first.length,"Unacknowledged crash tail not truncated");
        s.chunk(id,first.length,second.length,sha(second),new ByteArrayInputStream(second),null);s.chunk(id,2097152,last.length,sha(last),new ByteArrayInputStream(last),null);
        Files.write(new File(root,"large.bin").toPath(),bytes("keep existing"));r=s.finish(id,null);check(r.complete&&r.name.equals("large (1).bin")&&Arrays.equals(Files.readAllBytes(new File(root,r.name).toPath()),data),"Verified collision-safe commit failed");
        check(text(new File(root,"large.bin")).equals("keep existing"),"Existing file replaced");
        check(store(root,journals).finish(id,null).name.equals(r.name)&&root.listFiles((dir,name)->name.equals("large (2).bin")).length==0,"Finish retry duplicated a completed file");
        check(store(root,journals).begin(root,"large.bin",data.length,checksum).id.equals(id),"Unchanged completed receipt was not reused");
        phase(journals,id,1,-1);check(store(root,journals).status(id).complete,"Crash after rename but before receipt wasn't recovered");
        final Arc7ResumableUploads completed=store(root,journals);status(()->completed.cancel(id),"409");check(new File(root,r.name).isFile(),"Cancel deleted published file");
        Arc7ResumableUploads other=store(foreign,journals);status(()->other.status(id),"404");status(()->other.begin(root,"outside.bin",0,sha(new byte[0])),"403");
        for(String name:new String[]{"../x",".arc7-share-owned","x\nname"," ","a/b"})status(()->completed.begin(root,name,0,sha(new byte[0])),"400");
        Arc7ResumableUploads.Result bad=completed.begin(root,"tampered.bin",first.length,sha(first));completed.chunk(bad.id,0,first.length,sha(first),new ByteArrayInputStream(first),null);
        File partial=new File(root,".arc7-share-"+bad.id+".part");try(RandomAccessFile f=new RandomAccessFile(partial,"rw")){f.seek(99);f.write((first[99]^1)&255);}
        status(()->completed.finish(bad.id,null),"422");check(!new File(root,"tampered.bin").exists(),"Final-corrupt data was published");completed.cancel(bad.id);
        Arc7ResumableUploads.Result exp=completed.begin(root,"expired.bin",10,sha(new byte[10]));phase(journals,exp.id,0,System.currentTimeMillis()-Arc7ResumableUploads.RETENTION_MILLIS-1000L);status(()->completed.status(exp.id),"410");
        check(completed.inspect()[0]==1,"Expired partial not accounted for explicit cleanup");completed.discardPending();check(completed.inspect()[0]==0&&new File(root,r.name).isFile(),"Discard crossed saved-file boundary");
        List<String> pending=new ArrayList<>();for(int i=0;i<4;i++)pending.add(completed.begin(root,"pending"+i,1,sha(new byte[1])).id);status(()->completed.begin(root,"fifth",1,sha(new byte[1])),"409");completed.discardPending();
        Arc7ResumableUploads.Result locked=completed.begin(root,"locked",1,sha(new byte[]{2}));CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        InputStream slow=new InputStream(){boolean done;public int read(){if(done)return -1;done=true;entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return 2;}};
        Thread worker=new Thread(()->{try{completed.chunk(locked.id,0,1,sha(new byte[]{2}),slow,null);}catch(Throwable e){error.set(e);}});worker.start();check(entered.await(2,TimeUnit.SECONDS),"Slow chunk not entered");
        status(()->store(root,journals).status(locked.id),"423");release.countDown();worker.join(3000);check(!worker.isAlive()&&error.get()==null,"Serialized chunk failed");completed.cancel(locked.id);
        // Test a crash between prepared commit journal and final rename; then retry publishes just once.
        Arc7ResumableUploads.Result before=completed.begin(root,"before.bin",1,sha(new byte[]{9}));completed.chunk(before.id,0,1,sha(new byte[]{9}),new ByteArrayInputStream(new byte[]{9}),null);Arc7ResumableUploads.Result saved=completed.finish(before.id,null);
        Files.move(new File(root,saved.name).toPath(),new File(root,".arc7-share-"+before.id+".part").toPath());Files.write(new File(root,saved.name).toPath(),new byte[0]);phase(journals,before.id,1,-1);
        check(store(root,journals).finish(before.id,null).complete,"Crash before rename recovery failed");
        Arc7ResumableUploads.Result original=completed.begin(root,"mutated.bin",1,sha(new byte[]{3}));completed.chunk(original.id,0,1,sha(new byte[]{3}),new ByteArrayInputStream(new byte[]{3}),null);completed.finish(original.id,null);
        File changed=new File(root,"mutated.bin");long timestamp=changed.lastModified();Files.write(changed.toPath(),new byte[]{4});changed.setLastModified(timestamp);
        status(()->completed.status(original.id),"409");status(()->completed.finish(original.id,null),"409");
        Arc7ResumableUploads.Result replacement=completed.begin(root,"mutated.bin",1,sha(new byte[]{3}));check(!replacement.complete&&!replacement.id.equals(original.id)&&replacement.offset==0,"Init reused a same-size/same-mtime but mutated completed file");
        completed.chunk(replacement.id,0,1,sha(new byte[]{3}),new ByteArrayInputStream(new byte[]{3}),null);Arc7ResumableUploads.Result copied=completed.finish(replacement.id,null);
        check(copied.name.equals("mutated (1).bin")&&Files.readAllBytes(changed.toPath())[0]==4&&completed.status(copied.id).complete,"Receipt retirement changed saved data or failed to produce a verified new copy");
        rejects(()->completed.begin(root,"mutated.bin",1,sha(new byte[]{3}),()->false),"stopped share hashing completed receipt");
        System.out.println("PASS real durable upload store: chunk/whole SHA-256, rollback, offsets/tail recovery, process-instance restart, exact-folder binding, collision reservation, rehashed receipt reuse including same-size/same-mtime mutation, idempotent finish, before/after-rename crash replay, expiry/quota/busy gates and explicit partial-only cleanup");
    }
    private static final class Response {int status;String body,cookie,checksum;JSONObject json()throws Exception{return new JSONObject(body);}}
    private static Response request(String base,String method,String path,String token,String cookie,byte[] body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection(Proxy.NO_PROXY);c.setInstanceFollowRedirects(false);c.setConnectTimeout(3000);c.setReadTimeout(15000);c.setRequestMethod(method);
        if(token!=null)c.setRequestProperty("X-Arc7-Share",token);if(cookie!=null)c.setRequestProperty("Cookie",cookie);
        if(body!=null){c.setDoOutput(true);c.setFixedLengthStreamingMode(body.length);try(OutputStream out=c.getOutputStream()){out.write(body);}}
        Response r=new Response();r.status=c.getResponseCode();r.cookie=c.getHeaderField("Set-Cookie");r.checksum=c.getHeaderField("X-Arc7-SHA256");InputStream in=r.status>=400?c.getErrorStream():c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream();if(in!=null)try{byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}finally{in.close();}r.body=out.toString("UTF-8");c.disconnect();return r;
    }
    private static String login(String base,String token)throws Exception{Response r=request(base,"GET","/?t="+token,null,null,null);check(r.status==303&&r.cookie.contains("HttpOnly"),"Bearer exchange failed");return r.cookie.split(";")[0];}
    private static Arc7QrFileShareServer server(File root,File journal,String token)throws Exception{return new Arc7QrFileShareServer(root,token,new int[]{0},journal,name->new FileInputStream(new File(assets,name)));}
    private static void http()throws Exception {
        File root=folder(work,"http-root"),journals=folder(work,"http-private");String token="0123456789abcdef0123456789abcdef0123456789abcdef",newToken="abcdef0123456789abcdef0123456789abcdef0123456789";
        Arc7QrFileShareServer server=server(root,journals,token);server.start();byte[] empty=new byte[0],data=bytes("actual resumed HTTP payload");String id,base="http://127.0.0.1:"+server.getPort(),cookie=login(base,token);
        try{
            Response page=request(base,"GET","/",null,cookie,null);check(page.status==200&&page.body.contains("1 MiB chunks")&&page.body.contains("/sha256.js"),"Resume UI not served");
            Response js=request(base,"GET","/sha256.js",null,cookie,null);check(js.status==200&&js.body.contains("Sha256.prototype.update"),"Offline incremental hash asset missing");check(request(base,"GET","/upload.js",null,null,null).status==401,"Client assets bypassed authorization");
            Response init=request(base,"POST","/upload/init?path=&name=http.bin&size="+data.length+"&sha256="+sha(data),token,cookie,empty);check(init.status==200,"HTTP init failed: "+init.body);id=init.json().getString("id");
            byte[] prefix=Arrays.copyOf(data,7);check(request(base,"POST","/upload/chunk?id="+id+"&offset=0&sha256="+sha(prefix),token,cookie,prefix).json().optLong("offset",-1)==7,"HTTP chunk ack failed");
            check(request(base,"GET","/download?path=.arc7-share-"+id+".part",null,cookie,null).status==403,"Known partial path was served");
            Files.createSymbolicLink(new File(root,"not-a-partial.txt").toPath(),new File(root,".arc7-share-"+id+".part").toPath());
            check(request(base,"GET","/download?path=not-a-partial.txt",null,cookie,null).status==403,"Symlink exposed a hidden partial");
            check(!request(base,"GET","/",null,cookie,null).body.contains("not-a-partial.txt"),"Hidden-partial symlink appeared in listing");
            // A real peer disconnect half way through a declared body must not advance offset.
            Socket socket=new Socket("127.0.0.1",server.getPort());OutputStream out=socket.getOutputStream();String header="POST /upload/chunk?id="+id+"&offset=7&sha256="+sha(Arrays.copyOfRange(data,7,data.length))+" HTTP/1.1\r\nHost: 127.0.0.1:"+server.getPort()+"\r\nCookie: "+cookie+"\r\nX-Arc7-Share: "+token+"\r\nContent-Length: "+(data.length-7)+"\r\n\r\n";out.write(header.getBytes("US-ASCII"));out.write(data,7,2);out.flush();socket.shutdownOutput();while(socket.getInputStream().read()!=-1){}socket.close();
            check(request(base,"POST","/upload/status?id="+id,token,cookie,empty).json().optLong("offset",-1)==7,"HTTP interrupted chunk advanced checkpoint");
            check(request(base,"POST","/upload/status?id="+id,null,cookie,empty).status==403,"Control action bypassed CSRF header");
        }finally{server.stop();}
        server=server(root,journals,newToken);server.start();base="http://127.0.0.1:"+server.getPort();String oldCookie=cookie;cookie=login(base,newToken);
        try{
            check(request(base,"POST","/upload/status?id="+id,token,oldCookie,empty).status==401,"Old bearer survived a restarted share");
            check(request(base,"POST","/upload/status?id="+id,newToken,cookie,empty).json().optLong("offset",-1)==7,"New share lost durable offset");
            byte[] rest=Arrays.copyOfRange(data,7,data.length);check(request(base,"POST","/upload/chunk?id="+id+"&offset=7&sha256="+sha(rest),newToken,cookie,rest).status==200,"Resumed HTTP chunk failed");
            JSONObject done=request(base,"POST","/upload/finish?id="+id,newToken,cookie,empty).json();check(done.optBoolean("complete",false)&&done.getString("sha256").equals(sha(data))&&Arrays.equals(data,Files.readAllBytes(new File(root,"http.bin").toPath())),"HTTP final integrity receipt failed");
            check(request(base,"POST","/upload/finish?id="+id,newToken,cookie,empty).json().getString("name").equals("http.bin"),"HTTP finish not idempotent");
            check(request(base,"GET","/download?path=http.bin",null,cookie,null).body.equals(new String(data,"UTF-8")),"Saved bytes cannot be downloaded");
            byte[] mutated=data.clone();mutated[0]^=1;File destination=new File(root,"http.bin");long stamp=destination.lastModified();Files.write(destination.toPath(),mutated);destination.setLastModified(stamp);
            check(request(base,"POST","/upload/status?id="+id,newToken,cookie,empty).status==409,"HTTP status claimed a same-size changed copy was still verified");
            JSONObject replacement=request(base,"POST","/upload/init?path=&name=http.bin&size="+data.length+"&sha256="+sha(data),newToken,cookie,empty).json();
            check(!replacement.optBoolean("complete",false)&&!replacement.getString("id").equals(id)&&Arrays.equals(mutated,Files.readAllBytes(destination.toPath())),"HTTP init trusted a stale receipt or modified saved bytes");
            check(request(base,"POST","/upload/cancel?id="+replacement.getString("id"),newToken,cookie,empty).status==200,"HTTP cancellation failed");
            Response old=request(base,"POST","/upload?path=&name=legacy.txt&sha256="+sha(data),newToken,cookie,data);check(old.status==201&&old.checksum.equals(sha(data)),"Legacy small upload checksum header missing");
            check(request(base,"POST","/upload?path=&name=bad.txt&sha256="+sha(empty),newToken,cookie,data).status==422&&!new File(root,"bad.txt").exists(),"Legacy checksum mismatch published data");
            check(!DebugLog.snapshot().contains(token)&&!DebugLog.snapshot().contains(newToken)&&!DebugLog.snapshot().contains("http.bin"),"Transfer token/file name leaked into debug log");
        }finally{server.stop();}
        Arc7QrFileShareServer legacy=new Arc7QrFileShareServer(root,token,new int[]{0});legacy.start();
        try {
            base="http://127.0.0.1:"+legacy.getPort();cookie=login(base,token);
            Response page=request(base,"GET","/",null,cookie,null);
            check(page.status==200&&page.body.contains("Compatibility uploader")&&page.body.contains("8 MiB per file")&&!page.body.contains("src=\"/sha256.js\"")&&!page.body.contains("src=\"/upload.js\""),"Asset-less constructor served a broken or falsely resumable client");
            int start=page.body.indexOf("<script>var legacyBusy=")+8,end=page.body.indexOf("</script>",start);
            check(start>8&&end>start,"Compatibility client script missing");Files.write(new File(work,"legacy-client.js").toPath(),page.body.substring(start,end).getBytes("UTF-8"));
        }finally{legacy.stop();}
        System.out.println("PASS real HTTP resume: bundled offline client and functional asset-less compatibility fallback, bearer/cookie/CSRF guards, hidden partial refusal, socket truncation rollback, new-token share restart, acknowledged offset recovery, final checksum/idempotent receipt and legacy small-upload SHA-256");
    }
    public static void main(String[] args)throws Exception {work=folder(new File(args[0]),"reliability");assets=new File(args[1]);logging();cacheAndProfile();mediaJobs();mediaBudgets(new File(args[2]));resumable();http();}
}
