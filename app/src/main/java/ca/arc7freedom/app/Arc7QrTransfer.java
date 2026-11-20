package ca.arc7freedom.app;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.BitSet;
import java.util.UUID;
import java.util.zip.CRC32;

/** Offline optical file transport. The QR text contains bytes, never a URL. No Android APIs. */
public final class Arc7QrTransfer {
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int CHUNK_BYTES = 128;
    public static final int MAX_TEXT = 271; // fits the bundled version-10 QR encoder without truncation
    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private Arc7QrTransfer() {}

    public static final class Frame {
        public final boolean manifest;
        public final String id, name, sha256;
        public final int size, total, index;
        public final byte[] bytes;
        private Frame(boolean m, String id, String name, String sha, int size, int total, int index, byte[] b) {
            manifest = m; this.id = id; this.name = name; sha256 = sha;
            this.size = size; this.total = total; this.index = index; bytes = b;
        }
    }

    public static Frame parse(String text) throws IOException {
        if (text == null || text.length() > MAX_TEXT || !text.startsWith("A7Q1|")) throw bad("Not an Arc7 byte-transfer QR");
        int end = text.lastIndexOf('|');
        if (end < 0 || !crc(text.substring(0, end)).equals(text.substring(end + 1))) throw bad("QR checksum failed; rescan this frame");
        String[] p = text.substring(0, end).split("\\|", -1);
        if (p.length < 3 || !p[2].matches("[0-9a-f]{16}")) throw bad("Invalid transfer ID");
        if ("M".equals(p[1]) && p.length == 8) {
            int size = number(p[3], 0, MAX_BYTES), chunk = number(p[4], CHUNK_BYTES, CHUNK_BYTES);
            int total = number(p[5], 1, MAX_BYTES / CHUNK_BYTES);
            if (total != count(size) || !p[6].matches("[0-9a-f]{64}")) throw bad("Invalid transfer metadata");
            byte[] rawName = unbase64(p[7]);
            String name = new String(rawName, "UTF-8");
            if (rawName.length == 0 || rawName.length > 96 || !base64(name.getBytes("UTF-8")).equals(p[7])
                    || !safeName(name).equals(name)) throw bad("Unsafe file name");
            return new Frame(true, p[2], name, p[6], size, total, -1, null);
        }
        if ("D".equals(p[1]) && p.length == 6) {
            int total = number(p[4], 1, MAX_BYTES / CHUNK_BYTES);
            int index = number(p[3], 0, total - 1);
            byte[] data = unbase64(p[5]);
            if (data.length > CHUNK_BYTES) throw bad("QR payload is too large");
            return new Frame(false, p[2], "", "", -1, total, index, data);
        }
        throw bad("Unknown QR protocol version or frame");
    }

    public static final class Sender implements Closeable {
        public final String name, sha256, id;
        public final int size, total;
        private final File snapshot;
        private final RandomAccessFile in;
        public Sender(File source, File cache) throws Exception {
            if (!source.isFile() || !source.canRead() || source.length() > MAX_BYTES) throw bad("Choose a readable file of at most 1 MiB; use LAN sharing for larger files");
            name = safeName(source.getName());
            snapshot = File.createTempFile("arc7-qr-send-", ".part", cache);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            int length = 0;
            try {
                FileInputStream from = new FileInputStream(source);
                try {
                    FileOutputStream to = new FileOutputStream(snapshot);
                    try {
                        byte[] buffer = new byte[8192]; int n;
                        while ((n = from.read(buffer)) != -1) {
                            cancelled(); length += n;
                            if (length > MAX_BYTES) throw bad("The file grew beyond the 1 MiB optical-transfer limit");
                            to.write(buffer, 0, n); md.update(buffer, 0, n);
                        }
                    } finally { to.close(); }
                } finally { from.close(); }
                in = new RandomAccessFile(snapshot, "r");
            } catch (Exception error) { snapshot.delete(); throw error; }
            size = length; total = count(size); sha256 = hex(md.digest());
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        public String manifest() throws IOException {
            return seal("A7Q1|M|" + id + "|" + size + "|" + CHUNK_BYTES + "|" + total + "|" + sha256 + "|" + base64(name.getBytes("UTF-8")));
        }
        public synchronized String data(int index) throws IOException {
            if (index < 0 || index >= total) throw bad("Frame is out of range");
            byte[] bytes = new byte[Math.max(0, Math.min(CHUNK_BYTES, size - index * CHUNK_BYTES))];
            in.seek((long) index * CHUNK_BYTES); in.readFully(bytes);
            return seal("A7Q1|D|" + id + "|" + index + "|" + total + "|" + base64(bytes));
        }
        @Override public synchronized void close() throws IOException { try { in.close(); } finally { snapshot.delete(); } }
    }

    public static final class Receiver implements Closeable {
        public final Frame metadata;
        private final File partial;
        private final RandomAccessFile out;
        private final BitSet received = new BitSet();
        private int receivedCount;
        private boolean verified, closed;
        public Receiver(Frame header, File cache) throws IOException {
            if (!header.manifest) throw bad("Scan the file header first");
            metadata = header;
            partial = File.createTempFile("arc7-qr-receive-", ".part", cache);
            try {
                out = new RandomAccessFile(partial, "rw"); out.setLength(header.size);
            } catch (IOException error) { partial.delete(); throw error; }
        }
        /** Returns false for a repeat. Accepts out-of-order frames; never mixes transfers. */
        public synchronized boolean accept(Frame frame) throws IOException {
            if (closed) throw bad("Transfer is closed");
            if (!metadata.id.equals(frame.id) || frame.total != metadata.total) throw bad("Different transfer; finish or cancel the current file first");
            if (frame.manifest) {
                if (!metadata.sha256.equals(frame.sha256) || metadata.size != frame.size || !metadata.name.equals(frame.name)) throw bad("Conflicting file header");
                return false;
            }
            int expected = Math.max(0, Math.min(CHUNK_BYTES, metadata.size - frame.index * CHUNK_BYTES));
            if (frame.index < 0 || frame.index >= metadata.total || frame.bytes.length != expected) throw bad("Incorrect frame length");
            out.seek((long) frame.index * CHUNK_BYTES);
            if (received.get(frame.index)) {
                byte[] old = new byte[expected]; out.readFully(old);
                if (!java.util.Arrays.equals(old, frame.bytes)) throw bad("Conflicting duplicate frame; cancel and restart");
                return false;
            }
            out.write(frame.bytes); received.set(frame.index); receivedCount++; verified = false; return true;
        }
        public synchronized int receivedCount() { return receivedCount; }
        public synchronized boolean complete() { return receivedCount == metadata.total; }
        public synchronized String missing() {
            StringBuilder s = new StringBuilder(); int shown = 0;
            for (int i = received.nextClearBit(0); i < metadata.total && shown < 12; i = received.nextClearBit(i + 1)) {
                if (shown++ > 0) s.append(", "); s.append(i + 1);
            }
            if (metadata.total - receivedCount > shown) s.append(" …");
            return s.toString();
        }
        public synchronized void verify() throws Exception {
            if (closed || !complete()) throw bad("Frames are missing: " + missing());
            out.getFD().sync(); out.seek(0L);
            MessageDigest md = MessageDigest.getInstance("SHA-256"); byte[] b = new byte[8192]; int n;
            while ((n = out.read(b)) != -1) { cancelled(); md.update(b, 0, n); }
            if (!hex(md.digest()).equals(metadata.sha256)) throw bad("File SHA-256 mismatch; nothing was saved. Cancel and resend.");
            verified = true;
        }
        /** Atomically publishes a verified file, with exclusive name reservation, never overwriting. */
        public synchronized File save(File directory) throws Exception {
            if (!verified) verify();
            File dir = directory.getCanonicalFile();
            if ((!dir.isDirectory() && !dir.mkdirs()) || !dir.canWrite()) throw bad("Cannot write to the receive folder");
            File temp = File.createTempFile(".arc7-qr-", ".part", dir), target = null;
            boolean saved = false;
            try {
                FileOutputStream copy = new FileOutputStream(temp);
                try {
                    out.seek(0L); byte[] b = new byte[8192]; int n;
                    while ((n = out.read(b)) != -1) { cancelled(); copy.write(b, 0, n); }
                    copy.getFD().sync();
                } finally { copy.close(); }
                for (int i = 0; i < 10000; i++) {
                    File candidate = new File(dir, i == 0 ? metadata.name : numbered(metadata.name, i));
                    if (!candidate.getCanonicalFile().getParentFile().equals(dir)) throw bad("Unsafe receive path");
                    if (candidate.createNewFile()) { target = candidate; break; }
                }
                if (target == null || !temp.renameTo(target)) throw bad("Could not publish received file");
                saved = true; return target;
            } finally { temp.delete(); if (!saved && target != null) target.delete(); }
        }
        @Override public synchronized void close() throws IOException {
            if (!closed) { closed = true; try { out.close(); } finally { partial.delete(); } }
        }
    }

    private static String numbered(String name, int i) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) + " (" + i + ")" + name.substring(dot) : name + " (" + i + ")";
    }
    public static String safeName(String name) throws IOException {
        String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}/\\\\:|]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.length() == 0) n = "received-file";
        while (n.getBytes("UTF-8").length > 96) n = n.substring(0, n.offsetByCodePoints(n.length(), -1));
        return n;
    }
    private static int count(int size) { return Math.max(1, (size + CHUNK_BYTES - 1) / CHUNK_BYTES); }
    private static int number(String s, int min, int max) throws IOException {
        if (!s.matches("0|[1-9][0-9]{0,6}")) throw bad("Invalid frame number");
        int n; try { n = Integer.parseInt(s); } catch (NumberFormatException e) { throw bad("Invalid number"); }
        if (n < min || n > max) throw bad("Frame value is out of bounds"); return n;
    }
    private static String seal(String body) throws IOException {
        String frame = body + "|" + crc(body);
        if (frame.length() > MAX_TEXT) throw bad("QR frame exceeds encoder capacity"); return frame;
    }
    private static String crc(String s) throws IOException {
        CRC32 crc = new CRC32(); crc.update(s.getBytes("US-ASCII"));
        String v = Long.toHexString(crc.getValue()); return "00000000".substring(v.length()) + v;
    }
    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte v : b) { s.append("0123456789abcdef".charAt((v & 255) >>> 4)); s.append("0123456789abcdef".charAt(v & 15)); }
        return s.toString();
    }
    public static String base64(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length; i += 3) {
            int v = ((b[i] & 255) << 16) | (i + 1 < b.length ? (b[i + 1] & 255) << 8 : 0) | (i + 2 < b.length ? b[i + 2] & 255 : 0);
            s.append(B64.charAt(v >>> 18)); s.append(B64.charAt((v >>> 12) & 63));
            s.append(i + 1 < b.length ? B64.charAt((v >>> 6) & 63) : '='); s.append(i + 2 < b.length ? B64.charAt(v & 63) : '=');
        }
        return s.toString();
    }
    public static byte[] unbase64(String s) throws IOException {
        if (s.length() % 4 != 0 || s.length() > 172 || !s.matches("[A-Za-z0-9+/]*={0,2}")) throw bad("Invalid QR base64");
        int length = s.length() / 4 * 3 - (s.endsWith("==") ? 2 : s.endsWith("=") ? 1 : 0);
        byte[] out = new byte[length]; int p = 0;
        for (int i = 0; i < s.length(); i += 4) {
            int v = 0;
            for (int j = 0; j < 4; j++) v = (v << 6) | Math.max(0, B64.indexOf(s.charAt(i + j)));
            for (int j = 16; j >= 0 && p < length; j -= 8) out[p++] = (byte) (v >>> j);
        }
        if (!base64(out).equals(s)) throw bad("Noncanonical QR base64"); return out;
    }
    private static IOException bad(String message) { return new IOException(message); }
    private static void cancelled() throws IOException { if (Thread.currentThread().isInterrupted()) throw bad("QR transfer cancelled"); }
}
