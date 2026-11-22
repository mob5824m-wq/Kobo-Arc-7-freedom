package ca.arc7freedom.app;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.BitSet;
import java.util.zip.CRC32;

/** Offline optical file transport. The QR text contains bytes, never a URL. No Android APIs. */
public final class Arc7QrTransfer {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    public static final int LEGACY_MAX_BYTES = 1024 * 1024;
    public static final int CHUNK_BYTES = 512;
    public static final int MAX_TEXT = 1472; // bounded byte-mode QR; dense mode is optional
    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private Arc7QrTransfer() {}

    public static final class Frame {
        public final boolean manifest;
        public final String id, name, sha256;
        public final int size, total, index, version, chunk;
        public final byte[] bytes;
        private Frame(boolean m, String id, String name, String sha, int size, int total, int index, byte[] b, int version, int chunk) {
            manifest = m; this.id = id; this.name = name; sha256 = sha;
            this.size = size; this.total = total; this.index = index; bytes = b;
            this.version = version; this.chunk = chunk;
        }
    }

    private static int chunk(int value, boolean legacy) throws IOException {
        if (legacy ? value != 128 : value != 128 && value != 512 && value != 1024) throw bad("Unsupported QR density");
        return value;
    }
    private static int maximum(int version) { return version == 1 ? LEGACY_MAX_BYTES : MAX_BYTES; }
    public static Frame parse(String text) throws IOException {
        if (text == null || text.length() > MAX_TEXT || !(text.startsWith("A7Q1|") || text.startsWith("A7Q2|"))) throw bad("Not an Arc7 byte-transfer QR");
        int version = text.charAt(3) - '0';
        if (version == 1 && text.length() > 271) throw bad("Legacy QR frame is too large");
        int end = text.lastIndexOf('|');
        if (end < 0 || !crc(text.substring(0, end)).equals(text.substring(end + 1))) throw bad("QR checksum failed; rescan this frame");
        String[] p = text.substring(0, end).split("\\|", -1);
        if (p.length < 3 || !p[2].matches("[0-9a-f]{16}")) throw bad("Invalid transfer ID");
        if ("M".equals(p[1]) && p.length == 8) {
            int size = number(p[3], 0, maximum(version)), chunk = chunk(number(p[4], 128, 1024), version == 1);
            int total = number(p[5], 1, maximum(version) / chunk);
            if (total != count(size, chunk) || !p[6].matches("[0-9a-f]{64}")) throw bad("Invalid transfer metadata");
            byte[] rawName = unbase64(p[7]);
            String name = new String(rawName, "UTF-8");
            if (rawName.length == 0 || rawName.length > 96 || !base64(name.getBytes("UTF-8")).equals(p[7])
                    || !safeName(name).equals(name)) throw bad("Unsafe file name");
            return new Frame(true, p[2], name, p[6], size, total, -1, null, version, chunk);
        }
        if ("D".equals(p[1]) && p.length == (version == 1 ? 6 : 7)) {
            int chunk = version == 1 ? 128 : chunk(number(p[5], 128, 1024), false);
            int total = number(p[4], 1, maximum(version) / chunk);
            int index = number(p[3], 0, total - 1);
            byte[] data = unbase64(p[version == 1 ? 5 : 6]);
            if (data.length > chunk) throw bad("QR payload is too large");
            return new Frame(false, p[2], "", "", -1, total, index, data, version, chunk);
        }
        throw bad("Unknown QR protocol version or frame");
    }
    private static String manifest(Frame frame) throws IOException {
        return seal("A7Q" + frame.version + "|M|" + frame.id + "|" + frame.size + "|" + frame.chunk + "|" + frame.total + "|" + frame.sha256 + "|" + base64(frame.name.getBytes("UTF-8")));
    }
    private static InputStream open(File source) throws IOException {
        if (!source.isFile() || !source.canRead() || source.length() > MAX_BYTES) throw bad("Choose a readable file of at most 64 MiB");
        return new FileInputStream(source);
    }
    public static final class Sender implements Closeable {
        public interface ProgressListener { void onProgress(long copiedBytes, long totalBytes); }
        public final String name, sha256, id;
        public final int size, total, chunk, version;
        private final File snapshot;
        private final RandomAccessFile in;
        public Sender(File source, File cache) throws Exception { this(source, cache, CHUNK_BYTES, false); }
        public Sender(File source, File cache, int density, boolean legacy) throws Exception {
            this(open(source), source.getName(), cache, density, legacy, null, source.length());
        }
        public Sender(File source, File cache, int density, boolean legacy, ProgressListener progress) throws Exception {
            this(open(source), source.getName(), cache, density, legacy, progress, source.length());
        }
        /** Takes ownership of the source stream. Never loads a whole file into the tablet heap. */
        public Sender(InputStream source, String fileName, File cache, int density, boolean legacy) throws Exception {
            this(source, fileName, cache, density, legacy, null, -1L);
        }
        public Sender(InputStream source, String fileName, File cache, int density, boolean legacy,
                      ProgressListener progress, long expectedBytes) throws Exception {
            File temporary = null; RandomAccessFile reader = null;
            String safe = "", hash = ""; int length = 0, chosen = 0;
            try {
                chosen = chunk(density, legacy); safe = safeName(fileName);
                temporary = File.createTempFile("arc7-qr-send-", ".part", cache);
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                FileOutputStream to = new FileOutputStream(temporary);
                try {
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = source.read(buffer)) != -1) {
                        cancelled(); if (n == 0) continue;
                        if (length > (legacy ? LEGACY_MAX_BYTES : MAX_BYTES) - n) throw bad(legacy ? "Compatibility mode supports 1 MiB; choose Standard or Dense for larger files" : "The file exceeds the 64 MiB optical-transfer limit");
                        to.write(buffer, 0, n); md.update(buffer, 0, n); length += n;
                        if (progress != null) progress.onProgress(length, expectedBytes);
                    }
                    to.getFD().sync();
                } finally { to.close(); }
                hash = hex(md.digest()); reader = new RandomAccessFile(temporary, "r");
            } catch (Exception error) { if (temporary != null) temporary.delete(); throw error; }
            finally { try { source.close(); } catch (IOException ignored) {} }
            snapshot = temporary; in = reader; name = safe; sha256 = hash; size = length; chunk = chosen;
            version = legacy ? 1 : 2; total = count(size, chunk);
            // Re-selecting identical bytes/name/density yields the same ID so a receiver can resume.
            id = hex(MessageDigest.getInstance("SHA-256").digest((sha256 + "\n" + name + "\n" + version + "\n" + chunk).getBytes("UTF-8"))).substring(0, 16);
        }
        public String manifest() throws IOException {
            return seal("A7Q" + version + "|M|" + id + "|" + size + "|" + chunk + "|" + total + "|" + sha256 + "|" + base64(name.getBytes("UTF-8")));
        }
        public synchronized String data(int index) throws IOException {
            if (index < 0 || index >= total) throw bad("Frame is out of range");
            byte[] bytes = new byte[Math.max(0, Math.min(chunk, size - index * chunk))];
            in.seek((long) index * chunk); in.readFully(bytes);
            return seal("A7Q" + version + "|D|" + id + "|" + index + "|" + total + "|" + (version == 1 ? "" : chunk + "|") + base64(bytes));
        }
        @Override public synchronized void close() throws IOException { try { in.close(); } finally { snapshot.delete(); } }
    }

    public static final class Receiver implements Closeable {
        public final Frame metadata;
        private final File partial, journal;
        private final boolean resumable;
        private final File directory;
        private final RandomAccessFile out;
        private final FileLock receiveLock;
        private final BitSet received = new BitSet();
        private int receivedCount;
        private boolean verified, closed;
        public Receiver(Frame header, File cache) throws IOException { this(header, cache, false); }
        public Receiver(Frame header, File cache, boolean keepProgress) throws IOException {
            if (header == null || !header.manifest) throw bad("Scan the file header first");
            metadata = parse(manifest(header)); directory = cache; resumable = keepProgress;
            if (!cache.isDirectory() && !cache.mkdirs()) throw bad("Cannot create the private receive folder");
            partial = keepProgress ? new File(cache, "receive-" + header.id + ".part") : File.createTempFile("arc7-qr-receive-", ".part", cache);
            journal = keepProgress ? new File(cache, "receive-" + header.id + ".state") : null;
            boolean restoring = keepProgress && journal.isFile();
            if (keepProgress && !restoring && partial.exists()) throw bad("Unfinished data has no checkpoint. Discard it explicitly before restarting.");
            RandomAccessFile opened = null; FileLock held = null;
            try {
                opened = new RandomAccessFile(partial, "rw");
                try { held = opened.getChannel().tryLock(); }
                catch (OverlappingFileLockException busy) { throw bad("This receive is already open. Close its other transfer screen first."); }
                if (held == null) throw bad("This receive is open in another process. Close it before resuming.");
                // Read the journal only after owning the private file. Two activity instances
                // must never race a checkpoint or overwrite each other's received bitmap.
                if (restoring) {
                    DataInputStream state = new DataInputStream(new FileInputStream(journal));
                    try {
                        Frame saved = readHeader(state);
                        if (!manifest(saved).equals(manifest(header))) throw bad("Saved receive header conflicts with this file");
                        int size = state.readInt();
                        if (size != (header.total + 7) / 8) throw bad("Corrupt receive checkpoint");
                        byte[] bits = new byte[size]; state.readFully(bits); long expected = state.readLong();
                        CRC32 crc = new CRC32(); crc.update(bits);
                        if (crc.getValue() != expected || state.read() != -1) throw bad("Corrupt receive checkpoint");
                        for (int i = 0; i < header.total; i++) if ((bits[i / 8] & (1 << (i % 8))) != 0) { received.set(i); receivedCount++; }
                        if (!partial.isFile() || partial.length() != header.size) throw bad("Saved receive bytes are missing or truncated");
                    } finally { state.close(); }
                } else opened.setLength(header.size);
                out = opened; receiveLock = held;
                if (keepProgress && !restoring) checkpoint();
            } catch (IOException error) {
                if (opened != null) try { opened.close(); } catch (IOException ignored) {}
                if (!restoring && (held != null || !keepProgress)) { partial.delete(); if (journal != null) journal.delete(); }
                throw error;
            }
        }
        private static Frame readHeader(DataInputStream state) throws IOException {
            if (state.readInt() != 0x41375132) throw bad("Corrupt receive checkpoint");
            String header = state.readUTF(); if (header.length() > MAX_TEXT) throw bad("Corrupt receive checkpoint");
            Frame frame = parse(header); if (!frame.manifest) throw bad("Invalid saved header"); return frame;
        }
        /** Only private app storage; no unfinished bytes are exposed as a received file. */
        public synchronized void checkpoint() throws IOException {
            if (!resumable || closed) return;
            out.getFD().sync();
            File temp = File.createTempFile(".receive-state-", ".tmp", directory);
            try {
                FileOutputStream stream = new FileOutputStream(temp); DataOutputStream state = new DataOutputStream(stream);
                try {
                    state.writeInt(0x41375132); state.writeUTF(manifest(metadata));
                    byte[] bits = new byte[(metadata.total + 7) / 8];
                    for (int i = received.nextSetBit(0); i >= 0; i = received.nextSetBit(i + 1)) bits[i / 8] |= 1 << (i % 8);
                    CRC32 crc = new CRC32(); crc.update(bits);
                    state.writeInt(bits.length); state.write(bits); state.writeLong(crc.getValue()); state.flush(); stream.getFD().sync();
                } finally { state.close(); }
                if (!temp.renameTo(journal)) throw bad("Could not save receive progress; keep this screen open and free some storage");
            } finally { temp.delete(); }
        }
        /** Explicit recovery for a damaged/orphaned checkpoint; never touches saved/public files. */
        public static void discardPending(File directory, String id) throws IOException {
            if (id == null || !id.matches("[0-9a-f]{16}")) throw bad("Invalid saved transfer identity");
            File partial = new File(directory, "receive-" + id + ".part");
            RandomAccessFile opened = null; FileLock held = null;
            try {
                if (partial.isFile()) {
                    opened = new RandomAccessFile(partial, "rw");
                    try { held = opened.getChannel().tryLock(); }
                    catch (OverlappingFileLockException busy) { throw bad("This receive is still open. Close its transfer screen before discarding."); }
                    if (held == null) throw bad("This receive is still open in another process");
                }
                for (String suffix : new String[]{".part", ".state"}) {
                    File file = new File(directory, "receive-" + id + suffix);
                    if (!file.delete() && file.exists()) throw bad("Could not remove unfinished receive " + suffix);
                }
            } finally { if (opened != null) opened.close(); }
        }
        public static Frame pending(File directory, String id) throws IOException {
            if (id == null || !id.matches("[0-9a-f]{16}")) throw bad("Invalid saved transfer identity");
            DataInputStream state = new DataInputStream(new FileInputStream(new File(directory, "receive-" + id + ".state")));
            try { Frame frame = readHeader(state); if (!id.equals(frame.id)) throw bad("Saved transfer identity mismatch"); return frame; }
            finally { state.close(); }
        }
        public synchronized void discard() throws IOException {
            if (!closed) { closed = true; try { receiveLock.release(); } finally { out.close(); } }
            if (!partial.delete() && partial.exists()) throw bad("Could not remove unfinished bytes");
            if (journal != null && !journal.delete() && journal.exists()) throw bad("Could not remove saved checkpoint");
        }
        /** Returns false for a repeat. Accepts out-of-order frames; never mixes transfers. */
        public synchronized boolean accept(Frame frame) throws IOException {
            if (closed) throw bad("Transfer is closed");
            if (!metadata.id.equals(frame.id) || frame.total != metadata.total || frame.version != metadata.version || frame.chunk != metadata.chunk) throw bad("Different transfer; finish or cancel the current file first");
            if (frame.manifest) {
                if (!metadata.sha256.equals(frame.sha256) || metadata.size != frame.size || !metadata.name.equals(frame.name)) throw bad("Conflicting file header");
                return false;
            }
            int expected = Math.max(0, Math.min(metadata.chunk, metadata.size - frame.index * metadata.chunk));
            if (frame.index < 0 || frame.index >= metadata.total || frame.bytes.length != expected) throw bad("Incorrect frame length");
            out.seek((long) frame.index * metadata.chunk);
            if (received.get(frame.index)) {
                byte[] old = new byte[expected]; out.readFully(old);
                if (!java.util.Arrays.equals(old, frame.bytes)) throw bad("Conflicting duplicate frame; cancel and restart");
                return false;
            }
            out.write(frame.bytes); received.set(frame.index); receivedCount++; verified = false;
            if (resumable && (receivedCount % 32 == 0 || complete())) checkpoint();
            return true;
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
            if (!closed) {
                try { if (resumable) checkpoint(); }
                finally { closed = true; try { try { receiveLock.release(); } finally { out.close(); } } finally { if (!resumable) partial.delete(); } }
            }
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
    private static int count(int size, int chunk) { return Math.max(1, (size + chunk - 1) / chunk); }
    private static int number(String s, int min, int max) throws IOException {
        if (!s.matches("0|[1-9][0-9]{0,8}")) throw bad("Invalid frame number");
        int n; try { n = Integer.parseInt(s); } catch (NumberFormatException e) { throw bad("Invalid number"); }
        if (n < min || n > max) throw bad("Frame value is out of bounds"); return n;
    }
    private static String seal(String body) throws IOException {
        String frame = body + "|" + crc(body);
        if (frame.length() > (body.startsWith("A7Q1|") ? 271 : MAX_TEXT)) throw bad("QR frame exceeds encoder capacity"); return frame;
    }
    private static String crc(String s) throws IOException {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= 128) throw bad("QR framing must be ASCII");
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
        if (s.length() % 4 != 0 || s.length() > 1368 || !s.matches("[A-Za-z0-9+/]*={0,2}")) throw bad("Invalid QR base64");
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
