package ca.arc7freedom.app;

import java.io.IOException;

/** Parses the *bytes* of mp4a/esds, not a server MIME claim, for the Android-4 AAC-LC path. */
public final class LegacyAacConfig {
    private static final int[] RATES = {96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350};
    public final int frequencyIndex, sampleRate, channels;
    private LegacyAacConfig(int frequencyIndex, int channels) {
        this.frequencyIndex = frequencyIndex; sampleRate = RATES[frequencyIndex]; this.channels = channels;
    }

    public static LegacyAacConfig fromStsd(byte[] stsd) throws IOException {
        if (stsd == null || stsd.length < 52 || u32(stsd, 0) != stsd.length || !type(stsd, 4, "stsd")
                || u32(stsd, 8) != 0 || u32(stsd, 12) != 1 || !type(stsd, 20, "mp4a")) throw bad("Expected one clear mp4a sample entry");
        int entryEnd = end(stsd, 16, stsd.length);
        if (entryEnd != stsd.length || stsd[32] != 0 || stsd[33] != 0) throw bad("Unsupported encrypted/QuickTime AAC sample entry");
        int descriptors = 0;
        for (int p = 52; p < entryEnd;) {
            int next = end(stsd, p, entryEnd);
            if (type(stsd, p + 4, "sinf")) throw bad("Encrypted AAC is not supported");
            if (type(stsd, p + 4, "esds") && ++descriptors > 1) throw bad("Ambiguous AAC decoder configuration");
            p = next;
        }
        int p = 52;
        while (p < entryEnd) {
            int next = end(stsd, p, entryEnd);
            if (type(stsd, p + 4, "esds")) {
                if (next - p < 12 || u32(stsd, p + 8) != 0) throw bad("Unsupported/truncated esds version");
                Descriptor es = descriptor(stsd, p + 12, next, 3);
                int child = es.start + 3;
                if (child > es.end) throw bad("Truncated ES descriptor");
                int flags = stsd[es.start + 2] & 255;
                if ((flags & 128) != 0) child += 2;
                if ((flags & 64) != 0) { if (child >= es.end) throw bad("Truncated ES URL"); child += 1 + (stsd[child] & 255); }
                if ((flags & 32) != 0) child += 2;
                Descriptor config = descriptor(stsd, child, es.end, 4);
                if (config.end - config.start < 13 || (stsd[config.start] & 255) != 0x40
                        || ((stsd[config.start + 1] & 255) >>> 2) != 5) throw bad("Not MPEG-4 AAC audio");
                int d = config.start + 13;
                while (d < config.end) {
                    Descriptor payload = descriptor(stsd, d, config.end, -1);
                    if (payload.tag == 5) return fromAudioSpecificConfig(stsd, payload.start, payload.end);
                    d = payload.end;
                }
                throw bad("AAC decoder configuration is missing");
            }
            p = next;
        }
        throw bad("mp4a has no esds decoder configuration");
    }

    public static LegacyAacConfig fromAudioSpecificConfig(byte[] asc, int start, int end) throws IOException {
        Bits bits = new Bits(asc, start, end);
        int objectType = bits.read(5);
        if (objectType != 2) throw bad("Requires AAC-LC (object type 2), not HE-AAC/xHE-AAC (" + objectType + ")");
        int frequency = bits.read(4), channels = bits.read(4);
        if (frequency >= RATES.length || RATES[frequency] > 48000 || channels < 1 || channels > 2)
            throw bad("AAC-LC must be mono/stereo at 48 kHz or below");
        if (bits.read(1) != 0 || bits.read(1) != 0 || bits.read(1) != 0) throw bad("Unsupported AAC frame/core/extension mode");
        // A .40.2 label may still conceal implicit SBR. Accept the common explicit 'SBR absent' marker.
        if (bits.remaining() >= 17 && bits.read(11) == 0x2b7 && bits.read(5) == 5 && bits.read(1) != 0)
            throw bad("Implicit HE-AAC/SBR is not AAC-LC");
        return new LegacyAacConfig(frequency, channels);
    }

    /** Correct sample-entry channel/rate fields from the authoritative ASC, for old extractors. */
    public byte[] normalizedStsd(byte[] original) {
        byte[] copy = original.clone();
        copy[40] = 0; copy[41] = (byte) channels; copy[42] = 0; copy[43] = 16;
        copy[48] = (byte)(sampleRate >>> 8); copy[49] = (byte) sampleRate; copy[50] = 0; copy[51] = 0;
        return copy;
    }

    /** One unencrypted MPEG-4 ADTS header per unchanged 1024-sample AAC access unit. */
    public byte[] adtsHeader(int payloadBytes) throws IOException {
        int length = payloadBytes + 7;
        if (payloadBytes <= 0 || length > 8191) throw bad("AAC access unit exceeds ADTS length");
        return new byte[]{(byte)0xff,(byte)0xf1,(byte)((1 << 6) | (frequencyIndex << 2) | (channels >>> 2)),
                (byte)(((channels & 3) << 6) | (length >>> 11)), (byte)(length >>> 3), (byte)(((length & 7) << 5) | 31), (byte)0xfc};
    }
    private static final class Bits {
        final byte[] data; final int end; int position;
        Bits(byte[] b, int start, int end) throws IOException {
            if (b == null || start < 0 || end < start || end > b.length) throw bad("Invalid AAC configuration bounds");
            data = b; position = start * 8; this.end = end * 8;
        }
        int remaining() { return end - position; }
        int read(int n) throws IOException {
            if (n > remaining()) throw bad("Truncated AAC configuration");
            int value = 0; while (n-- > 0) { value = (value << 1) | ((data[position >>> 3] >>> (7 - (position & 7))) & 1); position++; } return value;
        }
    }
    private static final class Descriptor { int tag, start, end; }
    private static Descriptor descriptor(byte[] b, int p, int end, int expectedTag) throws IOException {
        if (p < 0 || p + 2 > end) throw bad("Truncated MPEG-4 descriptor");
        Descriptor d = new Descriptor(); d.tag = b[p++] & 255;
        if (expectedTag >= 0 && d.tag != expectedTag) throw bad("Unexpected MPEG-4 descriptor tag");
        int size = 0, count = 0, v;
        do { if (p >= end || ++count > 4) throw bad("Invalid descriptor length"); v = b[p++] & 255; size = (size << 7) | (v & 127); } while ((v & 128) != 0);
        if (size > end - p) throw bad("Descriptor extends outside esds"); d.start = p; d.end = p + size; return d;
    }
    private static long u32(byte[] b, int p) { return ((long)(b[p]&255)<<24) | ((long)(b[p+1]&255)<<16) | ((long)(b[p+2]&255)<<8) | (b[p+3]&255); }
    private static int end(byte[] b, int p, int end) throws IOException {
        if (end - p < 8) throw bad("Truncated AAC box"); long size = u32(b, p);
        if (size < 8 || size > end - p) throw bad("Invalid AAC box size"); return p + (int)size;
    }
    private static boolean type(byte[] b, int p, String type) {
        return p + 4 <= b.length && b[p] == type.charAt(0) && b[p+1] == type.charAt(1) && b[p+2] == type.charAt(2) && b[p+3] == type.charAt(3);
    }
    private static IOException bad(String s) { return new IOException(s); }
}
