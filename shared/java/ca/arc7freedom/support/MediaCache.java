package ca.arc7freedom.support;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Only cache/media-temporary is managed. Logs, saved media, vaults and receive partials are not cache. */
public final class MediaCache {
    private static File root;
    private static long limit = 256L * 1024L * 1024L;
    private static int leases;
    private static long activeLimit = limit;
    private MediaCache() {}
    public static synchronized void initialize(File privateCache, long bytes) throws IOException {
        File next = new File(privateCache.getCanonicalFile(), "media-temporary");
        if (!next.getCanonicalFile().equals(next.getAbsoluteFile())) throw new IOException("Unsafe temporary media directory");
        if (root != null && !root.equals(next) && leases > 0) throw new IOException("Temporary media is active");
        if (!next.isDirectory() && !next.mkdirs()) throw new IOException("Cannot create temporary media cache");
        root = next; setLimit(bytes);
    }
    public static synchronized File directory() { return root; }
    public static synchronized long limit() { return limit; }
    public static synchronized long activeLimit() { return activeLimit; }
    public static synchronized void setLimit(long bytes) {
        limit = Math.max(32L * 1024L * 1024L, Math.min(1024L * 1024L * 1024L, bytes));
        // Lowering a profile/limit must not abort an already-running preparation or download.
        if (leases == 0 || limit > activeLimit) activeLimit = limit;
    }
    public static synchronized boolean busy() { return leases > 0; }
    public static synchronized Lease lease() { if (leases == 0) activeLimit = limit; leases++; return new Lease(); }
    public static final class Lease implements Closeable {
        private boolean closed;
        private Lease() {}
        public void close() { synchronized (MediaCache.class) { if (!closed) { closed = true; leases--; if (leases == 0) activeLimit = limit; } } }
    }
    private static void collect(File directory, List<File> files, int depth) throws IOException {
        if (depth > 6) throw new IOException("Temporary media directory is too deep");
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Cannot read temporary media cache");
        for (File child : children) {
            // Refuse symlinks, not just symlink traversal: never unlink someone else's data.
            if (!child.getCanonicalFile().equals(child.getAbsoluteFile())) throw new IOException("Unsafe temporary cache entry");
            if (child.isDirectory()) collect(child, files, depth + 1);
            else if (child.isFile()) { if (files.size() >= 2048) throw new IOException("Too many temporary cache entries"); files.add(child); }
        }
    }
    private static List<File> files() throws IOException {
        List<File> files = new ArrayList<File>();
        if (root == null) return files;
        if (!root.getCanonicalFile().equals(root.getAbsoluteFile())) throw new IOException("Unsafe temporary cache root");
        collect(root, files, 0); return files;
    }
    public static synchronized long size() throws IOException { long total = 0; for (File file : files()) total += file.length(); return total; }
    public static synchronized long trim(long target) throws IOException {
        if (leases > 0) throw new IOException("Playback/download preparation is active. Stop it before clearing temporary cache.");
        List<File> files = files(); long total = 0, freed = 0;
        for (File file : files) total += file.length();
        Collections.sort(files, new Comparator<File>() { public int compare(File a, File b) { return a.lastModified() < b.lastModified() ? -1 : a.lastModified() == b.lastModified() ? 0 : 1; } });
        for (File file : files) {
            if (total <= Math.max(0, target)) break;
            long count = file.length(); if (!file.delete()) throw new IOException("Could not remove a temporary media file");
            total -= count; freed += count;
        }
        DebugLog.number("cache.bytes_freed", freed); return freed;
    }
    public static synchronized void reserve(long expectedBytes) throws IOException {
        if (root == null) return; // Legacy pure-Java callers/tests can supply a dedicated test cache.
        if (expectedBytes > limit) throw new IOException("Media needs more temporary space than this app's cache limit. Increase it in App tools.");
        if (leases == 0) trim(Math.max(0, limit - Math.max(0, expectedBytes)));
        if (size() + Math.max(0, expectedBytes) > limit) throw new IOException("Temporary media cache limit reached; stop other playback or increase the limit.");
    }
    public static synchronized void checkBudget() throws IOException {
        if (root != null && size() > activeLimit) throw new IOException("Temporary media cache limit reached. Increase it in App tools.");
    }
}
