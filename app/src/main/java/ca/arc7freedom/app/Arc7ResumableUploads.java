package ca.arc7freedom.app;

import ca.arc7freedom.support.DebugLog;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.locks.ReentrantLock;

/** Durable, bounded LAN upload journal. Independent of cache and of the expiring HTTP bearer. */
public final class Arc7ResumableUploads {
    public static final int CHUNK_BYTES = 1024 * 1024, MAX_PENDING = 4, MAX_RECEIPTS = 32;
    public static final long MAX_BYTES = 4L * 1024L * 1024L * 1024L;
    public static final long RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    // A share may be restarted while its previous worker unwinds. Never let two instances
    // append/commit the same journal concurrently; busy clients retry instead of queuing bodies.
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final File root, journals;
    public interface Running { boolean active(); }
    public static final class Failure extends IOException {
        public final String status;
        Failure(String status, String text) { super(text); this.status = status; }
    }
    public static final class Result {
        public final String id, name, sha256;
        public final long offset, size;
        public final boolean complete;
        Result(State s) { id=s.id; name=s.phase==2?s.destination:""; sha256=s.sha; offset=s.received; size=s.size; complete=s.phase==2; }
    }
    private static final class State {
        String id, root, relative, name, sha, destination="";
        long size, received, updated;
        int phase;
    }
    public Arc7ResumableUploads(File root, File journalDirectory) throws IOException {
        this.root = root.getCanonicalFile();
        if (!this.root.isDirectory()) throw new IOException("Shared folder unavailable");
        this.journals = journalDirectory.getAbsoluteFile();
        safe(this.journals);
        if (!this.journals.isDirectory() && !this.journals.mkdirs()) throw new IOException("Upload journal unavailable");
    }
    private static void enter() throws Failure { if (!LOCK.tryLock()) throw new Failure("423 Locked", "Another upload chunk/checksum is active. Retry in a moment."); }
    private static void safe(File file) throws IOException { if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) throw new IOException("Unsafe upload state entry"); }
    private File journal(String id) throws IOException {
        if (id==null || !id.matches("[0-9a-f]{32}")) throw new Failure("400 Bad Request", "Invalid upload identifier.");
        File file=new File(journals,id+".state"); safe(file); return file;
    }
    private File directory(State s) throws IOException {
        if (!root.getPath().equals(s.root)) throw new Failure("404 Not Found", "No upload in this shared folder. Select the original folder on the Kobo.");
        if (s.relative.indexOf('\\')>=0 || s.relative.indexOf('\u0000')>=0) throw new IOException("Invalid upload folder");
        File dir=new File(root,s.relative).getCanonicalFile();
        String base=root.getPath(), path=dir.getPath();
        if (!(path.equals(base)||path.startsWith(base+File.separator)) || !dir.isDirectory()) throw new Failure("403 Forbidden", "Upload folder unavailable.");
        return dir;
    }
    private File partial(State s) throws IOException { File part=new File(directory(s),".arc7-share-"+s.id+".part");safe(part);return part; }
    private File destination(State s) throws IOException {
        if (safeName(s.destination)==null) throw new IOException("Invalid upload destination");
        File dest=new File(directory(s),s.destination);safe(dest);return dest;
    }
    private State read(File file) throws IOException {
        safe(file); if (!file.isFile() || file.length()>16384) throw new Failure("404 Not Found", "Upload receipt unavailable; reselect the file to begin again.");
        DataInputStream in=new DataInputStream(new FileInputStream(file));
        State s=new State();
        try {
            if (in.readInt()!=0x41375531) throw new IOException("Invalid upload journal");
            s.id=in.readUTF();s.root=in.readUTF();s.relative=in.readUTF();s.name=in.readUTF();s.sha=in.readUTF();
            s.size=in.readLong();s.received=in.readLong();s.updated=in.readLong();s.phase=in.readInt();s.destination=in.readUTF();
            if(in.read()!=-1 || !file.getName().equals(s.id+".state") || !s.id.matches("[0-9a-f]{32}") || safeName(s.name)==null
                    || !validSha(s.sha) || s.size<0 || s.size>MAX_BYTES || s.received<0 || s.received>s.size || s.phase<0 || s.phase>2
                    || (s.phase>0 && (s.received!=s.size || safeName(s.destination)==null)) || s.updated<0) throw new IOException("Invalid upload journal");
        } finally { in.close(); }
        return s;
    }
    private State load(String id) throws IOException {
        State s=read(journal(id));directory(s);
        if (System.currentTimeMillis()-s.updated>RETENTION_MILLIS) throw new Failure("410 Gone", "Upload recovery expired after seven days. Discard pending uploads on the Kobo, then resend.");
        return s;
    }
    private void save(State s) throws IOException {
        s.updated=System.currentTimeMillis();
        File next=File.createTempFile("journal-", ".new", journals);
        try {
            FileOutputStream file=new FileOutputStream(next);DataOutputStream out=new DataOutputStream(file);
            try { out.writeInt(0x41375531);out.writeUTF(s.id);out.writeUTF(s.root);out.writeUTF(s.relative);out.writeUTF(s.name);out.writeUTF(s.sha);
                out.writeLong(s.size);out.writeLong(s.received);out.writeLong(s.updated);out.writeInt(s.phase);out.writeUTF(s.destination);out.flush();file.getFD().sync();
            } finally { out.close(); }
            if(!next.renameTo(journal(s.id)))throw new IOException("Could not checkpoint upload");
        } finally { if(next.exists())next.delete(); }
    }
    public Result begin(File dir, String name, long bytes, String sha) throws IOException { return begin(dir,name,bytes,sha,null); }
    public Result begin(File dir, String name, long bytes, String sha, Running running) throws IOException {
        if (safeName(name)==null || !validSha(sha) || bytes<0 || bytes>MAX_BYTES) throw new Failure("400 Bad Request", "Invalid file name, size or SHA-256.");
        enter();try {
            dir=dir.getCanonicalFile();
            State wanted=new State();wanted.root=root.getPath();
            String base=root.getPath(), path=dir.getPath();
            if (!(path.equals(base)||path.startsWith(base+File.separator)) || !dir.isDirectory() || !dir.canWrite()) throw new Failure("403 Forbidden", "Upload folder unavailable.");
            wanted.relative=path.equals(base)?"":path.substring(base.length()+1);
            wanted.name=name;wanted.size=bytes;wanted.sha=sha;
            int pending=0,receipts=0;State oldest=null;
            File[] files=journals.listFiles();if(files==null)throw new IOException("Cannot read upload journal");
            if(files.length>MAX_RECEIPTS+MAX_PENDING+16)throw new IOException("Upload journal needs attention; no files were removed");
            for(File file:files) {
                if(!file.getName().matches("[0-9a-f]{32}\\.state"))continue;
                State s=read(file);
                if(s.phase<2)pending++;else{receipts++;if(oldest==null||s.updated<oldest.updated)oldest=s;}
                if(s.root.equals(wanted.root)&&s.relative.equals(wanted.relative)&&s.name.equals(name)&&s.size==bytes&&s.sha.equals(sha)
                        &&System.currentTimeMillis()-s.updated<=RETENTION_MILLIS) {
                    // A receipt is not proof that the published file still has the same bytes.
                    // Rehash even when its name/size/mtime are unchanged before reporting complete.
                    if(s.phase==2 && !completedMatches(s,running)) {
                        if(!journal(s.id).delete())throw new IOException("Could not retire changed upload receipt");
                        receipts--; continue; // saved media is never removed; a new explicit upload gets a new reservation
                    }
                    if(s.phase==0)recoverTail(s);
                    if(s.phase==1)recoverCommit(s,running);
                    active(running);
                    DebugLog.event("lan.upload_resumed");return new Result(s);
                }
            }
            if(pending>=MAX_PENDING)throw new Failure("409 Conflict", "Four uploads are pending. Discard unwanted pending uploads on the Kobo before starting another.");
            if(receipts>=MAX_RECEIPTS&&oldest!=null&&!journal(oldest.id).delete())throw new IOException("Cannot rotate upload receipts");
            if(dir.getUsableSpace()>0&&bytes>dir.getUsableSpace())throw new Failure("507 Insufficient Storage", "Not enough free space for this file.");
            byte[] random=new byte[16];
            do{RANDOM.nextBytes(random);wanted.id=hex(random);}while(journal(wanted.id).exists()||partial(wanted).exists());
            File part=partial(wanted);if(!part.createNewFile())throw new IOException("Cannot reserve upload partial");
            try{save(wanted);}catch(IOException failure){part.delete();throw failure;}
            DebugLog.event("lan.upload_started");DebugLog.number("lan.upload_bytes",bytes);return new Result(wanted);
        }finally{LOCK.unlock();}
    }
    private void recoverTail(State s) throws IOException {
        File part=partial(s);
        if(!part.isFile()||part.length()<s.received)throw new Failure("409 Conflict", "Pending file is missing or truncated. Discard this upload and resend.");
        if(part.length()>s.received){RandomAccessFile f=new RandomAccessFile(part,"rw");try{f.setLength(s.received);f.getFD().sync();}finally{f.close();}}
    }
    private boolean completedMatches(State s,Running running)throws IOException {
        File saved=destination(s);
        return saved.isFile() && saved.length()==s.size && hash(saved,running).equals(s.sha);
    }
    private void requireCompleted(State s,Running running)throws IOException {
        if(!completedMatches(s,running))throw new Failure("409 Conflict","Saved copy changed after verification. Existing files were not modified; reselect the original source to upload a new collision-safe copy.");
    }
    public Result status(String id) throws IOException {return status(id,null);}
    public Result status(String id,Running running) throws IOException {
        enter();try{State s=load(id);if(s.phase==0)recoverTail(s);else if(s.phase==1)recoverCommit(s,running);else requireCompleted(s,running);return new Result(s);}finally{LOCK.unlock();}
    }
    public Result chunk(String id,long offset,long count,String sha,InputStream input,Running running) throws IOException {
        if(count<=0||count>CHUNK_BYTES||!validSha(sha))throw new Failure("400 Bad Request", "Chunks must be 1 byte to 1 MiB and include SHA-256.");
        enter();try {
            State s=load(id);
            if(s.phase!=0||offset!=s.received||count>s.size-s.received)throw new Failure("409 Conflict", "Upload offset changed. Query status and resume from the acknowledged offset.");
            recoverTail(s);RandomAccessFile out=new RandomAccessFile(partial(s),"rw");boolean committed=false;
            try {
                out.seek(offset);MessageDigest digest=digest();byte[] block=new byte[65536];long remaining=count;
                while(remaining>0){active(running);int n=input.read(block,0,(int)Math.min(block.length,remaining));if(n<0)throw new IOException("Interrupted upload chunk");if(n==0)continue;out.write(block,0,n);digest.update(block,0,n);remaining-=n;}
                if(!sha.equals(hex(digest.digest())))throw new Failure("422 Unprocessable Entity", "Chunk SHA-256 mismatch. The previous offset is unchanged; retry the chunk.");
                out.getFD().sync();active(running);s.received+=count;
                save(s);committed=true;DebugLog.number("lan.chunk_committed_offset",s.received);return new Result(s);
            } finally {
                try { if(!committed){out.setLength(offset);out.getFD().sync();DebugLog.event("lan.chunk_rolled_back");} } finally {out.close();}
            }
        }finally{LOCK.unlock();}
    }
    public Result finish(String id,Running running) throws IOException {
        enter();try {
            State s=load(id);
            if(s.phase==2){requireCompleted(s,running);return new Result(s);}
            if(s.phase==1){recoverCommit(s,running);if(s.phase==2)return new Result(s);}
            if(s.received!=s.size)throw new Failure("409 Conflict", "Bytes are still missing; resume before verifying.");
            File part=partial(s);recoverTail(s);
            if(!hash(part,running).equals(s.sha)){DebugLog.event("lan.final_checksum_mismatch");throw new Failure("422 Unprocessable Entity", "Final SHA-256 mismatch. Nothing was published. Discard this pending upload and resend.");}
            active(running);
            if(s.phase==0){
                File reserved=reserve(directory(s),s.name);s.destination=reserved.getName();s.phase=1;
                try{save(s);}catch(IOException e){if(reserved.length()==0)reserved.delete();throw e;}
            }
            File dest=destination(s);
            // A reservation is never allowed to replace non-empty content written by anyone else.
            if(!dest.exists()||dest.length()!=0){File reserved=reserve(directory(s),s.name);s.destination=reserved.getName();save(s);dest=reserved;}
            if(!part.renameTo(dest))throw new IOException("Could not publish verified file; retry completion");
            s.phase=2;save(s);DebugLog.number("lan.upload_verified_bytes",s.size);return new Result(s);
        }finally{LOCK.unlock();}
    }
    private void recoverCommit(State s,Running running) throws IOException {
        if(partial(s).exists())return;
        if(!completedMatches(s,running))throw new Failure("409 Conflict", "Upload commit needs attention; existing files were not changed.");
        active(running);s.phase=2;save(s);
    }
    public void cancel(String id) throws IOException {
        enter();try{State s=load(id);if(s.phase==2)throw new Failure("409 Conflict", "Already saved; a completed download is never deleted here.");discard(s);}finally{LOCK.unlock();}
    }
    private void discard(State s) throws IOException {
        File part=partial(s);if(part.exists()&&!part.delete())throw new IOException("Cannot discard upload partial");
        // A destination reservation/committed file is never deleted by cleanup, even if empty.
        if(!journal(s.id).delete())throw new IOException("Cannot discard upload journal");
        DebugLog.event("lan.pending_discarded");
    }
    /** Kobo-side only, explicit confirmation required. Includes expired receipts, never published files. */
    public long[] inspect() throws IOException {
        enter();try{long count=0,bytes=0;File[] files=journals.listFiles();if(files==null)throw new IOException("Upload journal unavailable");
            for(File file:files)if(file.getName().matches("[0-9a-f]{32}\\.state")){State s=read(file);if(s.root.equals(root.getPath())&&s.phase<2){count++;bytes+=partial(s).length();}}
            return new long[]{count,bytes};
        }finally{LOCK.unlock();}
    }
    public void discardPending() throws IOException {
        enter();try{File[] files=journals.listFiles();if(files==null)throw new IOException("Upload journal unavailable");
            for(File file:files)if(file.getName().matches("[0-9a-f]{32}\\.state")){State s=read(file);if(s.root.equals(root.getPath())&&s.phase<2)discard(s);}
        }finally{LOCK.unlock();}
    }
    private static File reserve(File dir,String name)throws IOException {
        int dot=name.lastIndexOf('.');String base=dot>0?name.substring(0,dot):name,ext=dot>0?name.substring(dot):"";
        for(int i=0;i<=9999;i++){File candidate=new File(dir,i==0?name:base+" ("+i+")"+ext);safe(candidate);if(candidate.createNewFile())return candidate;}
        throw new IOException("Too many duplicate names");
    }
    private static void active(Running running)throws IOException{if(Thread.currentThread().isInterrupted()||(running!=null&&!running.active()))throw new IOException("File share stopped; acknowledged chunks are retained");}
    private static MessageDigest digest()throws IOException{try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IOException("SHA-256 unavailable");}}
    private static String hash(File file,Running running)throws IOException{active(running);MessageDigest sha=digest();InputStream in=new FileInputStream(file);try{byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){active(running);sha.update(b,0,n);}active(running);return hex(sha.digest());}finally{in.close();}}
    public static boolean validSha(String s){return s!=null&&s.matches("[0-9a-f]{64}");}
    private static String hex(byte[] bytes){char[] digits="0123456789abcdef".toCharArray(),out=new char[bytes.length*2];for(int i=0;i<bytes.length;i++){out[i*2]=digits[(bytes[i]&255)>>>4];out[i*2+1]=digits[bytes[i]&15];}return new String(out);}
    public static String safeName(String value){
        if(value==null||value.length()==0||value.length()>220||!value.equals(value.trim())||value.equals(".")||value.equals("..")||value.startsWith(".arc7-share-")||value.indexOf('/')>=0||value.indexOf('\\')>=0)return null;
        for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))return null;
        try{if(value.getBytes("UTF-8").length>220)return null;}catch(Exception impossible){return null;}return value;
    }
}
