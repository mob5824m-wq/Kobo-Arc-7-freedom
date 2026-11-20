package ca.arc7freedom.support;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Per-process/per-APK opt-in profile; never interrupts playback, downloads or camera work. */
public final class RuntimePolicy {
    private static volatile boolean lowRam;
    private static final Set<String> pendingArtwork = new HashSet<String>();
    private static final class ArtworkTask implements Runnable {
        final String key; final Runnable work;
        ArtworkTask(String key,Runnable work){this.key=key;this.work=work;}
        public void run(){try{work.run();}finally{discard();}}
        void discard(){if(key!=null)synchronized(pendingArtwork){pendingArtwork.remove(key);}}
    }
    private static void discardQueued() {
        List<Runnable> dropped=new ArrayList<Runnable>();artwork.getQueue().drainTo(dropped);
        for(Runnable task:dropped)if(task instanceof ArtworkTask)((ArtworkTask)task).discard();
    }
    private static final ThreadPoolExecutor artwork = new ThreadPoolExecutor(3, 3, 20L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(24), new ThreadFactory() {
                private int next;
                public Thread newThread(Runnable task) { Thread t = new Thread(task, "Arc7-Artwork-" + (++next)); t.setDaemon(true); return t; }
            });
    static { artwork.allowCoreThreadTimeOut(true); }
    private RuntimePolicy() {}
    public static boolean lowRam() { return lowRam; }
    public static int artworkEdge() { return lowRam ? 128 : 256; }
    public static int thumbnailEntries() { return lowRam ? 8 : 32; }
    public static int wallpaperEdge() { return lowRam ? 96 : 160; }
    public static int mediaJobs() { return lowRam ? 1 : 3; }
    public static synchronized void setLowRam(boolean on) {
        lowRam = on;
        int count = on ? 1 : 3;
        if (on) { discardQueued(); artwork.setCorePoolSize(count); artwork.setMaximumPoolSize(count); }
        else { artwork.setMaximumPoolSize(count); artwork.setCorePoolSize(count); }
        DebugLog.number("profile.low_ram", on ? 1 : 0);
    }
    public static boolean artwork(Runnable work) { return artwork(null,work); }
    /** Identity is an in-memory de-duplication key only; it is NEVER written to the log. */
    public static boolean artwork(String identity,Runnable work) {
        if(identity!=null)synchronized(pendingArtwork){if(!pendingArtwork.add(identity))return false;}
        ArtworkTask task=new ArtworkTask(identity,work);
        try { artwork.execute(task); return true; }
        catch (RejectedExecutionException full) { task.discard(); DebugLog.event("artwork.queue_full"); return false; }
    }
    public static void trimMemory() { discardQueued(); DebugLog.event("memory.trim"); }
}
