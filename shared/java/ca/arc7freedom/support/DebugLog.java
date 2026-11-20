package ca.arc7freedom.support;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Private, bounded structured events. No API accepts messages, URLs, commands or user content. */
public final class DebugLog {
    public static final int FILE_BYTES = 128 * 1024, FILE_COUNT = 3, EXPORT_BYTES = 64 * 1024;
    private static File directory;
    private static boolean enabled = true;
    private static Thread.UncaughtExceptionHandler installed;
    private DebugLog() {}

    public static synchronized void initialize(File filesDirectory, String owner, boolean on) {
        directory = null;
        enabled = on;
        try {
            File candidate = new File(filesDirectory.getCanonicalFile(), "diagnostics");
            if (!candidate.getCanonicalFile().equals(candidate.getAbsoluteFile())) return;
            if (!candidate.isDirectory() && !candidate.mkdirs()) return;
            if(!restrict(candidate,true))return;
            directory = candidate;
            append("process.start owner=" + identifier(owner));
        } catch (Exception unavailable) { directory = null; }
    }
    public static synchronized void setEnabled(boolean on) { enabled = on; }
    public static synchronized boolean isEnabled() { return enabled; }
    /** Codes and class identifiers only: arbitrary input is rejected, never partially redacted. */
    private static String identifier(String text) {
        return text != null && text.length() <= 180 && text.matches("[A-Za-z_$][A-Za-z0-9_.$-]*") ? text : "redacted";
    }
    public static synchronized void event(String code) { append("event=" + identifier(code)); }
    public static synchronized void number(String code, long value) { append("event=" + identifier(code) + " value=" + value); }
    public static synchronized void lifecycle(String code, String className) {
        append("event=" + identifier(code) + " component=" + identifier(className));
    }
    public static synchronized void error(String code, Throwable failure) {
        append("event=" + identifier(code) + " error=" + identifier(failure == null ? "unknown" : failure.getClass().getName()));
        // Throwable messages / toString / causes can contain secrets, URLs and document text.
        // Record only bounded Java frame identifiers and numeric lines, never those messages.
        try {
            StackTraceElement[] frames = failure == null ? null : failure.getStackTrace();
            for (int i = 0; frames != null && i < frames.length && i < 12; i++) {
                StackTraceElement frame = frames[i];
                append("frame=" + identifier(frame.getClassName()) + "." + identifier(frame.getMethodName()) + " line=" + frame.getLineNumber());
            }
        } catch (Throwable unavailable) { /* Logging must never replace a failure. */ }
    }
    public static synchronized void installCrashHandler() {
        if (installed != null) return;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        installed = new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread thread, Throwable failure) {
                try { error("process.uncaught", failure); } catch (Throwable ignored) {}
                if (previous != null) previous.uncaughtException(thread, failure);
            }
        };
        Thread.setDefaultUncaughtExceptionHandler(installed);
    }
    private static boolean restrict(File file,boolean folder) {
        boolean ok=file.setReadable(false,false)&file.setWritable(false,false)&file.setExecutable(false,false);
        ok=file.setReadable(true,true)&file.setWritable(true,true)&ok;
        if(folder)ok=file.setExecutable(true,true)&ok;
        return ok;
    }
    private static File file(int index) throws IOException {
        File file = new File(directory, index == 0 ? "events.log" : "events." + index + ".log");
        if (!directory.getCanonicalFile().equals(directory.getAbsoluteFile()) || !file.getCanonicalFile().equals(file.getAbsoluteFile())) throw new IOException("Unsafe debug log entry");
        return file;
    }
    private static void append(String text) {
        if (!enabled || directory == null) return;
        try {
            byte[] line = (System.currentTimeMillis() + " " + text + "\n").getBytes("US-ASCII");
            if (file(0).length() + line.length > FILE_BYTES) {
                if (file(FILE_COUNT - 1).exists() && !file(FILE_COUNT - 1).delete()) return;
                for (int i = FILE_COUNT - 2; i >= 0; i--)
                    if (file(i).exists() && !file(i).renameTo(file(i + 1))) return;
            }
            FileOutputStream output = new FileOutputStream(file(0), true);
            try { if(restrict(file(0),false))output.write(line); } finally { output.close(); }
        } catch (Throwable unavailable) { /* full/unavailable storage is not an app crash */ }
    }
    public static synchronized String snapshot() throws IOException {
        if (directory == null) return "Local debug log unavailable.\n";
        ByteArrayOutputStream all = new ByteArrayOutputStream(EXPORT_BYTES);
        for (int i = FILE_COUNT - 1; i >= 0; i--) {
            File source = file(i);
            if (!source.isFile()) continue;
            FileInputStream input = new FileInputStream(source);
            try {
                long skip = Math.max(0, source.length() - EXPORT_BYTES);
                while (skip > 0) { long n = input.skip(skip); if (n <= 0) break; skip -= n; }
                byte[] block = new byte[4096]; int count;
                while ((count = input.read(block)) != -1) {
                    all.write(block, 0, count);
                    if (all.size() > EXPORT_BYTES) {
                        byte[] previous = all.toByteArray(); all.reset();
                        all.write(previous, previous.length - EXPORT_BYTES, EXPORT_BYTES);
                    }
                }
            } finally { input.close(); }
        }
        String text = new String(all.toByteArray(), "US-ASCII");
        if (text.length() == EXPORT_BYTES) { int newline = text.indexOf('\n'); if (newline >= 0) text = text.substring(newline + 1); }
        return text.length() == 0 ? "No local debug events.\n" : text;
    }
    public static synchronized void clear() throws IOException {
        if (directory == null) return;
        for (int i = 0; i < FILE_COUNT; i++) if (file(i).exists() && !file(i).delete()) throw new IOException("Could not clear a debug log");
    }
}
