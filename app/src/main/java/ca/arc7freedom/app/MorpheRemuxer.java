package ca.arc7freedom.app;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java ISO-BMFF (MP4) reader / writer used to turn YouTube's 2026 DASH
 * `adaptiveFormats` (video-only H.264 + audio-only AAC, both fragmented MP4) into a
 * single, seekable, fragmented MP4 that the Android 4.2 Stagefright `MediaPlayer`
 * on the Kobo Arc 7 can actually play.
 *
 * Why this exists
 * ---------------
 * Since 2026 YouTube no longer serves progressive (muxed) `formats` to any InnerTube
 * client that still works anonymously — `formats` comes back empty and only
 * `adaptiveFormats` (video-only `avc1` + audio-only `mp4a`, `initRange`/`indexRange`
 * DASH segments) plus HLS are returned. That is why YouTube / YouTube Music pulled
 * metadata fine but `VideoView`/`MediaPlayer` had nothing to open and every stream
 * attempt failed.
 *
 * This class is deliberately free of any `android.*` imports so the exact same code
 * runs on the tablet and inside the host JVM regression suite.
 *
 * Output layout (all sizes known up front, so an exact `Content-Length` can be sent):
 *
 *   ftyp (from the video init) | merged moov (video trak + audio trak + mvex/trex)
 *   | styp/sidx | video moof+mdat | audio moof+mdat | ... interleaved by decode time
 *
 * Fragment payloads are copied byte-for-byte; only `mfhd.sequence_number`,
 * `tfhd.track_ID` and (when present) `tfhd.base_data_offset` are patched, so `trun`
 * sample tables and data offsets remain valid.
 */
public final class MorpheRemuxer {

    /** Fetches an inclusive byte range of a remote (DASH) track. */
    public interface RangeFetcher {
        byte[] fetch(long startInclusive, long endInclusive) throws Exception;
    }

    /** Metadata for one DASH track (`adaptiveFormats[]` entry). */
    public static final class Track {
        public final String mimeType;
        public final long contentLength;   // full size of the DASH file, -1 if unknown
        public final long initEnd;         // last byte (inclusive) of `initRange`
        public final long durationMs;
        public final RangeFetcher fetcher;

        public Track(String mimeType, long contentLength, long initEnd, long durationMs, RangeFetcher fetcher) {
            this.mimeType = mimeType;
            this.contentLength = contentLength;
            this.initEnd = Math.max(0L, initEnd);
            this.durationMs = Math.max(0L, durationMs);
            this.fetcher = fetcher;
        }

        public long bodyStart() {
            return initEnd + 1L;
        }

        public long bodyLength() {
            if (contentLength <= 0L) return -1L;
            long len = contentLength - bodyStart();
            return len > 0L ? len : 0L;
        }
    }

    public interface Progress {
        void onBytes(long absoluteOffset, long totalBytes);
        void onStage(String stage);
    }

    /** Result of building the merged stream header. */
    public static final class Plan {
        public byte[] ftyp;
        public byte[] moov;
        public long totalBytes = -1L;
        public int videoTrackId = 1;
        public int audioTrackId = 2;
        public int videoTimescale = 1000;
        public int audioTimescale = 1000;

        public long headerLength() {
            return (long) ftyp.length + (long) moov.length;
        }
    }

    private MorpheRemuxer() {}

    /* ======================================================================
     * 1. Public API
     * ====================================================================== */

    /**
     * Builds the merged ftyp+moov for a video-only + audio-only pair and computes the
     * exact final file length so the HTTP server can announce `Content-Length` before
     * a single media byte has been downloaded.
     */
    public static Plan plan(Track video, Track audio) throws Exception {
        byte[] vInit = video.fetcher.fetch(0L, video.initEnd);
        byte[] aInit = audio.fetcher.fetch(0L, audio.initEnd);

        Plan plan = new Plan();
        byte[] ftyp;
        byte[] vMoov = null;
        byte[] aMoov = null;
        for (Box b : boxes(vInit, 0, vInit.length)) {
            if ("moov".equals(b.type)) vMoov = slice(vInit, b.start, b.size);
        }
        for (Box b : boxes(aInit, 0, aInit.length)) {
            if ("moov".equals(b.type)) aMoov = slice(aInit, b.start, b.size);
        }
        if (vMoov == null) throw new IOException("Video init segment has no moov box");
        if (aMoov == null) throw new IOException("Audio init segment has no moov box");
        // Always emit our own conservative `isom/iso2/avc1/mp41` header: YouTube's DASH init
        // sometimes uses `iso6`/`dash` major brands that Android 4.2's MPEG4Extractor rejects.
        ftyp = defaultFtyp();

        plan.ftyp = ftyp;
        plan.moov = mergeMoov(vMoov, aMoov, video.durationMs, audio.durationMs, plan);
        long vBody = video.bodyLength();
        long aBody = audio.bodyLength();
        if (vBody >= 0L && aBody >= 0L) {
            plan.totalBytes = (long) plan.ftyp.length + (long) plan.moov.length + vBody + aBody;
        } else {
            plan.totalBytes = -1L;
        }
        return plan;
    }

    /**
     * Streams video+audio DASH tracks into `out` as one interleaved fragmented MP4.
     * The caller is responsible for writing {@link Plan#ftyp} / {@link Plan#moov} at the
     * very start (or calling {@link #remux} which does it).
     */
    public static void remux(Track video, Track audio, RandomAccessFile out, Progress progress) throws Exception {
        Plan plan = plan(video, audio);
        out.seek(0L);
        out.setLength(0L);
        out.write(plan.ftyp);
        out.write(plan.moov);
        if (progress != null) {
            progress.onBytes(out.length(), plan.totalBytes);
            progress.onStage("moov merged (" + plan.moov.length + " bytes, "
                    + (plan.totalBytes > 0 ? (plan.totalBytes / 1048576L + " MB") : "length unknown") + ")");
        }

        FragmentStream vs = new FragmentStream(video, plan.videoTrackId);
        FragmentStream as = new FragmentStream(audio, plan.audioTrackId);
        long seq = 1L;

        Fragment fv = vs.next();
        Fragment fa = as.next();
        while (fv != null || fa != null) {
            Fragment pick;
            if (fv == null) { pick = fa; fa = as.next(); }
            else if (fa == null) { pick = fv; fv = vs.next(); }
            else if (fv.decodeTimeSec <= fa.decodeTimeSec) { pick = fv; fv = vs.next(); }
            else { pick = fa; fa = as.next(); }

            long moofOffset = out.getFilePointer();
            byte[] patched = pick.moof.clone();
            patchMoof(patched, pick.newTrackId, seq, moofOffset);
            seq++;
            out.write(patched);
            out.write(pick.mdat);
            if (progress != null) progress.onBytes(out.length(), plan.totalBytes);
        }

        // Pad to the announced length if a track ended early (or if `sidx`/`styp` boxes were
        // dropped) using a standards-compliant `free` box so no parser ever sees garbage.
        if (plan.totalBytes > out.length()) {
            long pad = plan.totalBytes - out.length();
            byte[] zeros = new byte[(int) Math.min(65536L, pad)];
            if (pad >= 8L) {
                byte[] header = new byte[8];
                put32(header, 0, (int) Math.min(Integer.MAX_VALUE, pad));
                header[4] = 'f'; header[5] = 'r'; header[6] = 'e'; header[7] = 'e';
                out.write(header);
                pad -= 8L;
            }
            while (pad > 0L) {
                int n = (int) Math.min((long) zeros.length, pad);
                out.write(zeros, 0, n);
                pad -= n;
            }
        }
        out.getFD().sync();
        if (progress != null) progress.onStage("complete");
    }

    /**
     * Copies a single-track DASH file (audio-only `mp4a` or video-only `avc1`) into `out`,
     * defragmenting DASH `moof`/`mdat` segments into a classic non-fragmented ISO-BMFF MP4
     * (`ftyp` + `moov` with full `stsd`/`stts`/`stsc`/`stsz`/`stco` sample tables + `mdat`)
     * so Android 4.2.2 Stagefright (`MPEG4Extractor`) plays it natively.
     */
    public static void copyTrack(Track track, RandomAccessFile out, Progress progress) throws Exception {
        if (track.initEnd > 0L) {
            try {
                remuxSingleTrackClassicMp4(track, out, progress);
                return;
            } catch (Throwable ignored) {
                // Fall back to direct range copy if the input is already a progressive stream.
            }
        }
        out.seek(0L);
        out.setLength(0L);
        long total = track.contentLength;
        long pos = 0L;
        long step = 524288L;
        while (total <= 0L || pos < total) {
            long end = (total > 0L) ? Math.min(total - 1L, pos + step - 1L) : (pos + step - 1L);
            byte[] chunk = track.fetcher.fetch(pos, end);
            if (chunk == null || chunk.length == 0) break;
            out.write(chunk);
            pos += chunk.length;
            if (progress != null) progress.onBytes(out.length(), total);
            if (total <= 0L && chunk.length < step) break;
        }
        out.getFD().sync();
        if (progress != null) progress.onStage("complete");
    }

    /**
     * Defragments a video-only DASH track + an audio-only DASH track into a single classic
     * non-fragmented ISO-BMFF MP4 (`ftyp` + `moov` with populated `stsd`, `stts`, `stss`,
     * `ctts`, `stsc`, `stsz`, `stco` tables + interleaved `mdat`) that Android 4.2.2
     * `libstagefright` (`MPEG4Extractor.cpp`) plays natively without needing `moof`/`trun`.
     */
    public static void remuxClassicMp4(final Track video,
                                       final Track audio,
                                       RandomAccessFile out,
                                       final Progress progress) throws Exception {
        final byte[][] inits = new byte[2][];
        final Throwable[] initErr = new Throwable[2];
        Thread tA = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    inits[1] = audio.fetcher.fetch(0L, audio.initEnd);
                } catch (Throwable t) {
                    initErr[1] = t;
                }
            }
        }, "Arc7-MuxInitAudio");
        tA.start();
        try {
            inits[0] = video.fetcher.fetch(0L, video.initEnd);
        } catch (Throwable t) {
            initErr[0] = t;
        }
        tA.join();
        if (initErr[0] != null) {
            if (initErr[0] instanceof Exception) throw (Exception) initErr[0];
            throw new IOException(initErr[0]);
        }
        if (initErr[1] != null) {
            if (initErr[1] instanceof Exception) throw (Exception) initErr[1];
            throw new IOException(initErr[1]);
        }

        ClassicTrackBuilder vBuilder = new ClassicTrackBuilder(inits[0], 1, true, video.durationMs);
        ClassicTrackBuilder aBuilder = new ClassicTrackBuilder(inits[1], 2, false, audio.durationMs);

        final long approxTotal = (video.contentLength > 0L ? video.contentLength : 0L)
                + (audio.contentLength > 0L ? audio.contentLength : 0L);

        // Fetch video and audio fragment streams in parallel into memory queues for 2x throughput.
        final List<Fragment> vFrags = new ArrayList<Fragment>();
        final List<Fragment> aFrags = new ArrayList<Fragment>();
        final Throwable[] streamErr = new Throwable[2];
        final long[] bytesFetched = new long[]{inits[0].length + inits[1].length};

        Thread vThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    FragmentStream vs = new FragmentStream(video, 1, inits[0]);
                    Fragment f;
                    while ((f = vs.next()) != null) {
                        synchronized (vFrags) {
                            vFrags.add(f);
                            bytesFetched[0] += f.moof.length + f.mdat.length;
                            if (progress != null) progress.onBytes(bytesFetched[0], approxTotal);
                        }
                    }
                } catch (Throwable t) {
                    streamErr[0] = t;
                }
            }
        }, "Arc7-MuxFetchVideo");

        Thread aThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    FragmentStream as = new FragmentStream(audio, 2, inits[1]);
                    Fragment f;
                    while ((f = as.next()) != null) {
                        synchronized (vFrags) {
                            aFrags.add(f);
                            bytesFetched[0] += f.moof.length + f.mdat.length;
                            if (progress != null) progress.onBytes(bytesFetched[0], approxTotal);
                        }
                    }
                } catch (Throwable t) {
                    streamErr[1] = t;
                }
            }
        }, "Arc7-MuxFetchAudio");

        vThread.start();
        aThread.start();
        vThread.join();
        aThread.join();

        if (streamErr[0] != null && vFrags.isEmpty()) {
            if (streamErr[0] instanceof Exception) throw (Exception) streamErr[0];
            throw new IOException(streamErr[0]);
        }
        if (streamErr[1] != null && aFrags.isEmpty()) {
            if (streamErr[1] instanceof Exception) throw (Exception) streamErr[1];
            throw new IOException(streamErr[1]);
        }

        java.io.File spoolFile = java.io.File.createTempFile("arc7_mdat_", ".bin");
        RandomAccessFile spool = new RandomAccessFile(spoolFile, "rw");
        try {
            int vi = 0, ai = 0;
            while (vi < vFrags.size() || ai < aFrags.size()) {
                boolean takeVideo;
                if (vi >= vFrags.size()) takeVideo = false;
                else if (ai >= aFrags.size()) takeVideo = true;
                else takeVideo = vFrags.get(vi).decodeTimeSec <= aFrags.get(ai).decodeTimeSec;

                if (takeVideo) {
                    vBuilder.consumeFragment(vFrags.get(vi++), spool);
                } else {
                    aBuilder.consumeFragment(aFrags.get(ai++), spool);
                }
            }
            if (vBuilder.sampleCount() == 0) {
                throw new IOException("No video samples decoded from DASH stream");
            }

            long mdatPayloadLen = spool.length();
            byte[] ftyp = defaultFtyp();
            List<ClassicTrackBuilder> tracks = new ArrayList<ClassicTrackBuilder>();
            tracks.add(vBuilder);
            if (aBuilder.sampleCount() > 0) tracks.add(aBuilder);

            byte[] dummyMoov = buildClassicMoov(vBuilder.mvhdBox, tracks, 0L);
            long mdatDataOffset = (long) ftyp.length + (long) dummyMoov.length + 8L;
            byte[] finalMoov = buildClassicMoov(vBuilder.mvhdBox, tracks, mdatDataOffset);

            out.seek(0L);
            out.setLength(0L);
            out.write(ftyp);
            out.write(finalMoov);

            byte[] mdatHdr = new byte[8];
            put32(mdatHdr, 0, 8L + mdatPayloadLen);
            mdatHdr[4] = 'm'; mdatHdr[5] = 'd'; mdatHdr[6] = 'a'; mdatHdr[7] = 't';
            out.write(mdatHdr);

            spool.seek(0L);
            byte[] copyBuf = new byte[65536];
            int n;
            long totalOut = (long) ftyp.length + (long) finalMoov.length + 8L + mdatPayloadLen;
            while ((n = spool.read(copyBuf)) > 0) {
                out.write(copyBuf, 0, n);
            }
            out.getFD().sync();
            if (progress != null) {
                progress.onBytes(totalOut, totalOut);
                progress.onStage("complete");
            }
        } finally {
            try { spool.close(); } catch (Throwable ignored) {}
            try { spoolFile.delete(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Defragments a single DASH track (audio-only `.m4a` or video-only `.mp4`) into a classic
     * non-fragmented ISO-BMFF file (`ftyp` + `moov` with full `stbl` + `mdat`).
     */
    public static void remuxSingleTrackClassicMp4(Track track, RandomAccessFile out, Progress progress) throws Exception {
        byte[] init = track.fetcher.fetch(0L, track.initEnd);
        boolean isVideo = track.mimeType != null && track.mimeType.startsWith("video/");
        ClassicTrackBuilder builder = new ClassicTrackBuilder(init, 1, isVideo, track.durationMs);

        java.io.File spoolFile = java.io.File.createTempFile("arc7_smdat_", ".bin");
        RandomAccessFile spool = new RandomAccessFile(spoolFile, "rw");
        try {
            FragmentStream fs = new FragmentStream(track, 1, init);
            Fragment f;
            long fetched = init.length;
            while ((f = fs.next()) != null) {
                builder.consumeFragment(f, spool);
                fetched += f.moof.length + f.mdat.length;
                if (progress != null) progress.onBytes(fetched, track.contentLength);
            }
            if (builder.sampleCount() == 0) {
                throw new IOException("No samples decoded from single-track DASH stream");
            }

            long mdatPayloadLen = spool.length();
            byte[] ftyp = defaultFtyp();
            List<ClassicTrackBuilder> tracks = new ArrayList<ClassicTrackBuilder>();
            tracks.add(builder);

            byte[] dummyMoov = buildClassicMoov(builder.mvhdBox, tracks, 0L);
            long mdatDataOffset = (long) ftyp.length + (long) dummyMoov.length + 8L;
            byte[] finalMoov = buildClassicMoov(builder.mvhdBox, tracks, mdatDataOffset);

            out.seek(0L);
            out.setLength(0L);
            out.write(ftyp);
            out.write(finalMoov);

            byte[] mdatHdr = new byte[8];
            put32(mdatHdr, 0, 8L + mdatPayloadLen);
            mdatHdr[4] = 'm'; mdatHdr[5] = 'd'; mdatHdr[6] = 'a'; mdatHdr[7] = 't';
            out.write(mdatHdr);

            spool.seek(0L);
            byte[] copyBuf = new byte[65536];
            int n;
            long totalOut = (long) ftyp.length + (long) finalMoov.length + 8L + mdatPayloadLen;
            while ((n = spool.read(copyBuf)) > 0) {
                out.write(copyBuf, 0, n);
            }
            out.getFD().sync();
            if (progress != null) {
                progress.onBytes(totalOut, totalOut);
                progress.onStage("complete");
            }
        } finally {
            try { spool.close(); } catch (Throwable ignored) {}
            try { spoolFile.delete(); } catch (Throwable ignored) {}
        }
    }

    /* ======================================================================
     * 2. Box scanning helpers
     * ====================================================================== */

    static final class Box {
        String type;
        int start;      // first byte of the box header (>=0 relative to array start)
        int size;       // total box size
        int contentStart;
        int contentEnd;

        int contentLen() {
            return contentEnd - contentStart;
        }
    }

    static List<Box> boxes(byte[] d, int off, int limit) {
        List<Box> list = new ArrayList<Box>();
        int p = off;
        int hardLimit = Math.min(limit, d.length);
        while (p + 8 <= hardLimit) {
            long size = u32(d, p);
            String type = new String(d, p + 4, 4);
            int header = 8;
            if (size == 1L) {
                if (p + 16 > hardLimit) break;
                size = i64(d, p + 8);
                header = 16;
            } else if (size == 0L) {
                size = hardLimit - p;
            }
            if (size < header || p + size > hardLimit) break;
            Box b = new Box();
            b.type = type;
            b.start = p;
            b.size = (int) size;
            b.contentStart = p + header;
            b.contentEnd = p + (int) size;
            list.add(b);
            p += (int) size;
        }
        return list;
    }

    static Box findBox(List<Box> list, String type) {
        for (Box b : list) {
            if (b.type.equals(type)) return b;
        }
        return null;
    }

    static byte[] slice(byte[] d, int start, int len) {
        byte[] out = new byte[len];
        System.arraycopy(d, start, out, 0, len);
        return out;
    }

    static long u32(byte[] d, int off) {
        return ((long) (d[off] & 0xFF) << 24)
                | ((long) (d[off + 1] & 0xFF) << 16)
                | ((long) (d[off + 2] & 0xFF) << 8)
                | (long) (d[off + 3] & 0xFF);
    }

    static void put32(byte[] d, int off, long value) {
        d[off] = (byte) ((value >> 24) & 0xFF);
        d[off + 1] = (byte) ((value >> 16) & 0xFF);
        d[off + 2] = (byte) ((value >> 8) & 0xFF);
        d[off + 3] = (byte) (value & 0xFF);
    }

    static long i64(byte[] d, int off) {
        return (u32(d, off) << 32) | u32(d, off + 4);
    }

    static void put64(byte[] d, int off, long value) {
        put32(d, off, (value >>> 32) & 0xFFFFFFFFL);
        put32(d, off + 4, value & 0xFFFFFFFFL);
    }

    static byte[] concat(List<byte[]> parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    static byte[] box(String type, byte[] payload) {
        byte[] out = new byte[8 + payload.length];
        put32(out, 0, out.length);
        for (int i = 0; i < 4; i++) out[4 + i] = (byte) type.charAt(i);
        System.arraycopy(payload, 0, out, 8, payload.length);
        return out;
    }

    static byte[] defaultFtyp() {
        byte[] p = new byte[]{
                'i', 's', 'o', 'm', 0, 0, 2, 0,
                'i', 's', 'o', 'm', 'i', 's', 'o', '2',
                'a', 'v', 'c', '1', 'm', 'p', '4', '1'
        };
        return box("ftyp", p);
    }

    /* ======================================================================
     * 3. moov merge
     * ====================================================================== */

    static int trackIdOf(byte[] trak) {
        List<Box> kids = boxes(trak, 8, trak.length);
        Box tkhd = findBox(kids, "tkhd");
        if (tkhd == null) return 1;
        int version = trak[tkhd.contentStart] & 0xFF;
        int off = tkhd.contentStart + (version == 1 ? 20 : 12);
        return off + 4 <= trak.length ? (int) u32(trak, off) : 1;
    }

    static int timescaleOf(byte[] trak) {
        List<Box> kids = boxes(trak, 8, trak.length);
        Box mdia = findBox(kids, "mdia");
        if (mdia == null) return 1000;
        List<Box> mdiaKids = boxes(trak, mdia.contentStart, mdia.contentEnd);
        Box mdhd = findBox(mdiaKids, "mdhd");
        if (mdhd == null) return 1000;
        int version = trak[mdhd.contentStart] & 0xFF;
        int off = mdhd.contentStart + (version == 1 ? 20 : 12);
        if (off + 4 > trak.length) return 1000;
        long ts = u32(trak, off);
        return ts > 0L && ts < Integer.MAX_VALUE ? (int) ts : 1000;
    }

    static void patchTrackId(byte[] trak, int newId) {
        List<Box> kids = boxes(trak, 8, trak.length);
        Box tkhd = findBox(kids, "tkhd");
        if (tkhd == null) return;
        int version = trak[tkhd.contentStart] & 0xFF;
        int off = tkhd.contentStart + (version == 1 ? 20 : 12);
        if (off + 4 <= trak.length) put32(trak, off, newId);
    }

    static void patchTkhdDuration(byte[] trak, long durationMs, int timescale) {
        List<Box> kids = boxes(trak, 8, trak.length);
        Box tkhd = findBox(kids, "tkhd");
        if (tkhd == null) return;
        int version = trak[tkhd.contentStart] & 0xFF;
        long scaled = timescale > 0 ? (durationMs * timescale) / 1000L : durationMs;
        if (version == 1) {
            int off = tkhd.contentStart + 28;
            if (off + 8 <= trak.length) put64(trak, off, scaled);
        } else {
            int off = tkhd.contentStart + 20;
            if (off + 4 <= trak.length) put32(trak, off, scaled);
        }
    }

    static void patchTrexTrackId(byte[] trex, int newId) {
        if (trex.length >= 16) put32(trex, 12, newId);
    }

    static void patchMvhd(byte[] moovOrMvhd, long durationMs, int nextTrackId, boolean isMoov) {
        int base = 0;
        byte[] mvhd = moovOrMvhd;
        if (isMoov) {
            List<Box> kids = boxes(moovOrMvhd, 8, moovOrMvhd.length);
            Box b = findBox(kids, "mvhd");
            if (b == null) return;
            mvhd = new byte[b.size];
            System.arraycopy(moovOrMvhd, b.start, mvhd, 0, b.size);
            base = 8; // content starts after box header
        } else {
            base = 8;
        }
        int version = mvhd[base] & 0xFF;
        int tsOff = base + (version == 1 ? 20 : 12);
        if (tsOff + 4 > mvhd.length) return;
        long timescale = u32(mvhd, tsOff);
        if (timescale <= 0L) timescale = 1000L;
        long scaled = (durationMs * timescale) / 1000L;
        if (version == 1) {
            int dOff = base + 24;
            if (dOff + 8 <= mvhd.length) put64(mvhd, dOff, scaled);
        } else {
            int dOff = base + 16;
            if (dOff + 4 <= mvhd.length) put32(mvhd, dOff, scaled);
        }
        int nextOff = base + (version == 1 ? 108 : 96);
        if (nextOff + 4 <= mvhd.length) put32(mvhd, nextOff, nextTrackId);
        if (isMoov) {
            List<Box> kids = boxes(moovOrMvhd, 8, moovOrMvhd.length);
            Box b = findBox(kids, "mvhd");
            if (b != null) System.arraycopy(mvhd, 0, moovOrMvhd, b.start, mvhd.length);
        }
    }

    static byte[] mergeMoov(byte[] vMoov, byte[] aMoov, long vDurMs, long aDurMs, Plan plan) throws IOException {
        List<Box> vKids = boxes(vMoov, 8, vMoov.length);
        List<Box> aKids = boxes(aMoov, 8, aMoov.length);
        Box vMvhd = findBox(vKids, "mvhd");
        Box vTrak = findBox(vKids, "trak");
        Box aTrak = findBox(aKids, "trak");
        if (vMvhd == null || vTrak == null || aTrak == null) {
            throw new IOException("moov is missing mvhd/trak boxes");
        }

        byte[] videoTrak = slice(vMoov, vTrak.start, vTrak.size);
        byte[] audioTrak = slice(aMoov, aTrak.start, aTrak.size);
        int vOrigId = trackIdOf(videoTrak);
        int aOrigId = trackIdOf(audioTrak);
        int vId = 1;
        int aId = (vOrigId == 2) ? 3 : 2;
        plan.videoTrackId = vId;
        plan.audioTrackId = aId;
        plan.videoTimescale = timescaleOf(videoTrak);
        plan.audioTimescale = timescaleOf(audioTrak);

        if (vOrigId != vId) patchTrackId(videoTrak, vId);
        if (aOrigId != aId) patchTrackId(audioTrak, aId);
        if (aOrigId != aId) {
            // `trun`/`tfhd` in fragments still carry the original id; FragmentStream patches those.
        }
        patchTkhdDuration(videoTrak, vDurMs, plan.videoTimescale);
        patchTkhdDuration(audioTrak, aDurMs, plan.audioTimescale);

        byte[] mvhd = slice(vMoov, vMvhd.start, vMvhd.size);

        // trex defaults from each side (if present) so the merged mvex stays consistent.
        byte[] vTrex = null;
        byte[] aTrex = null;
        Box vMvexBox = findBox(vKids, "mvex");
        if (vMvexBox != null) {
            for (Box b : boxes(vMoov, vMvexBox.contentStart, vMvexBox.contentEnd)) {
                if ("trex".equals(b.type)) { vTrex = slice(vMoov, b.start, b.size); break; }
            }
        }
        Box aMvexBox = findBox(aKids, "mvex");
        if (aMvexBox != null) {
            for (Box b : boxes(aMoov, aMvexBox.contentStart, aMvexBox.contentEnd)) {
                if ("trex".equals(b.type)) { aTrex = slice(aMoov, b.start, b.size); break; }
            }
        }
        if (vTrex == null) vTrex = defaultTrex(1, 1000);
        if (aTrex == null) aTrex = defaultTrex(1, 1024);
        patchTrexTrackId(vTrex, vId);
        patchTrexTrackId(aTrex, aId);

        List<byte[]> mvexKids = new ArrayList<byte[]>();
        mvexKids.add(vTrex);
        mvexKids.add(aTrex);
        byte[] mvex = box("mvex", concat(mvexKids));

        byte[] moovPayload = concat(java.util.Arrays.asList(mvhd, videoTrak, audioTrak, mvex));
        byte[] moov = box("moov", moovPayload);
        patchMvhd(moov, Math.max(vDurMs, aDurMs), Math.max(vId, aId) + 1, true);
        return moov;
    }

    static byte[] defaultTrex(int trackId, int defaultSize) {
        byte[] p = new byte[24];
        put32(p, 4, trackId);
        put32(p, 8, 1);
        put32(p, 12, 0);
        put32(p, 16, 0);
        put32(p, 20, 0);
        return box("trex", p);
    }

    /* ======================================================================
     * 4. Fragment streaming & patching
     * ====================================================================== */

    static final class Fragment {
        byte[] moof;
        byte[] mdat;
        double decodeTimeSec;
        int newTrackId;
    }

    /**
     * Reads one DASH track sequentially in {moof, ... , mdat} units, keeping every byte
     * that lives between the init segment and the track end.
     */
    static final class FragmentStream {
        private final Track track;
        private final int newTrackId;
        private final int timescale;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private long nextFetchStart;
        private long fetchEndInclusive;
        private boolean eof;

        FragmentStream(Track track, int newTrackId) throws Exception {
            this(track, newTrackId, track.fetcher.fetch(0L, track.initEnd));
        }

        FragmentStream(Track track, int newTrackId, byte[] init) throws Exception {
            this.track = track;
            this.newTrackId = newTrackId;
            byte[] moov = null;
            for (Box b : boxes(init, 0, init.length)) {
                if ("moov".equals(b.type)) moov = slice(init, b.start, b.size);
            }
            this.timescale = moov != null ? timescaleOfTrackMoov(moov) : 1000;
            this.nextFetchStart = track.bodyStart();
            this.fetchEndInclusive = track.contentLength > 0L ? track.contentLength - 1L : Long.MAX_VALUE;
            this.eof = track.contentLength > 0L && nextFetchStart > fetchEndInclusive;
        }

        private static int timescaleOfTrackMoov(byte[] moov) {
            for (Box b : boxes(moov, 8, moov.length)) {
                if ("trak".equals(b.type)) {
                    byte[] trak = slice(moov, b.start, b.size);
                    return timescaleOf(trak);
                }
            }
            return 1000;
        }

        private boolean fill(long wantBytes) throws Exception {
            if (pending.size() >= wantBytes) return true;
            if (eof) return false;
            long end = Math.min(fetchEndInclusive, nextFetchStart + Math.max(524288L, wantBytes) - 1L);
            byte[] chunk = track.fetcher.fetch(nextFetchStart, end);
            if (chunk == null || chunk.length == 0) {
                eof = true;
                return false;
            }
            pending.write(chunk, 0, chunk.length);
            nextFetchStart += chunk.length;
            if (track.contentLength > 0L && nextFetchStart > fetchEndInclusive) eof = true;
            return true;
        }

        Fragment next() throws Exception {
            while (true) {
                byte[] buf = pending.toByteArray();
                if (buf.length < 8) {
                    if (!fill(8)) return null;
                    continue;
                }
                long size = u32(buf, 0);
                String type = new String(buf, 4, 4);
                int header = 8;
                if (size == 1L) {
                    if (buf.length < 16) { if (!fill(16)) return null; continue; }
                    size = i64(buf, 8);
                    header = 16;
                }
                if (size < header || size > 64L * 1024L * 1024L) {
                    // Stream is not what we expect; drop a byte and resynchronise.
                    pending.reset();
                    pending.write(buf, 1, buf.length - 1);
                    continue;
                }
                if (buf.length < size) {
                    if (!fill((int) size)) return null;
                    continue;
                }
                byte[] whole = slice(buf, 0, (int) size);
                trimPending((int) size);

                if ("moof".equals(type)) {
                    // `moof` must be followed by `mdat` (possibly after styp/sidx/ssix).
                    ByteArrayOutputStream group = new ByteArrayOutputStream();
                    group.write(whole, 0, whole.length);
                    byte[] mdat = null;
                    while (mdat == null) {
                        byte[] rest = pending.toByteArray();
                        if (rest.length < 8) {
                            if (!fill(8)) break;
                            continue;
                        }
                        long s2 = u32(rest, 0);
                        String t2 = new String(rest, 4, 4);
                        int h2 = 8;
                        if (s2 == 1L) {
                            if (rest.length < 16) { if (!fill(16)) break; continue; }
                            s2 = i64(rest, 8);
                            h2 = 16;
                        }
                        if (s2 < h2 || s2 > 128L * 1024L * 1024L) break;
                        if (rest.length < s2) { if (!fill((int) s2)) break; continue; }
                        byte[] b2 = slice(rest, 0, (int) s2);
                        trimPending((int) s2);
                        if ("mdat".equals(t2) || "styp".equals(t2)) {
                            group.write(b2, 0, b2.length);
                        }
                        if ("mdat".equals(t2)) mdat = b2;
                        else if (!("styp".equals(t2) || "sidx".equals(t2) || "ssix".equals(t2)
                                || "prft".equals(t2) || "emsg".equals(t2))) {
                            // Unexpected box; keep it and stop grouping.
                            if (!"mdat".equals(t2)) { mdat = new byte[0]; }
                        }
                    }
                    if (mdat == null) return null;
                    byte[] g = group.toByteArray();
                    Fragment f = new Fragment();
                    f.moof = slice(g, 0, whole.length);
                    // Everything after the `moof` (interstitial boxes + `mdat`) is copied verbatim.
                    f.mdat = slice(g, whole.length, g.length - whole.length);
                    f.decodeTimeSec = decodeTimeSeconds(f.moof, timescale);
                    f.newTrackId = newTrackId;
                    return f;
                }

                // Leading boxes (`styp` / `sidx` / `ssix` / `free`) carry no samples and their
                // `sidx` byte offsets would be invalid after muxing — drop them and keep reading.
                continue;
            }
        }

        private void trimPending(int count) {
            byte[] buf = pending.toByteArray();
            pending.reset();
            if (count < buf.length) pending.write(buf, count, buf.length - count);
        }
    }

    static double decodeTimeSeconds(byte[] moof, int timescale) {
        long base = -1L;
        for (Box traf : boxes(moof, 8, moof.length)) {
            if (!"traf".equals(traf.type)) continue;
            for (Box b : boxes(moof, traf.contentStart, traf.contentEnd)) {
                if (!"tfdt".equals(b.type)) continue;
                int v = moof[b.contentStart] & 0xFF;
                if (v == 1 && b.contentStart + 12 <= moof.length) {
                    base = i64(moof, b.contentStart + 4);
                } else if (b.contentStart + 8 <= moof.length) {
                    base = u32(moof, b.contentStart + 4);
                }
                break;
            }
            if (base >= 0L) break;
        }
        if (base < 0L) return 0.0d;
        return (double) base / (double) Math.max(1, timescale);
    }

    static void patchMoof(byte[] moof, int newTrackId, long sequence, long moofOutputOffset) {
        for (Box top : boxes(moof, 8, moof.length)) {
            if ("mfhd".equals(top.type)) {
                put32(moof, top.contentStart + 4, sequence);
                continue;
            }
            if (!"traf".equals(top.type)) continue;
            for (Box b : boxes(moof, top.contentStart, top.contentEnd)) {
                if (!"tfhd".equals(b.type)) continue;
                int flags = ((moof[b.contentStart + 1] & 0xFF) << 16)
                        | ((moof[b.contentStart + 2] & 0xFF) << 8)
                        | (moof[b.contentStart + 3] & 0xFF);
                put32(moof, b.contentStart + 4, newTrackId);
                if ((flags & 0x000001) != 0 && b.contentStart + 16 <= moof.length) {
                    put64(moof, b.contentStart + 8, moofOutputOffset);
                }
            }
        }
    }

    /* ======================================================================
     * 5. Classic non-fragmented ISO-BMFF MP4 builder for Android 4.2 Stagefright
     * ====================================================================== */

    static final class ClassicTrackBuilder {
        final int newTrackId;
        final boolean isVideo;
        final long fallbackDurationMs;
        byte[] mvhdBox;
        byte[] tkhdBox;
        byte[] mdhdBox;
        byte[] hdlrBox;
        byte[] mediaHeaderBox; // vmhd or smhd
        byte[] dinfBox;
        byte[] stsdBox;
        int timescale = 1000;
        int trexDefDuration = 0;
        int trexDefSize = 0;
        int trexDefFlags = 0;

        int[] sampleDurations = new int[4096];
        int[] sampleSizes = new int[4096];
        int[] sampleCts = new int[4096];
        boolean[] sampleSync = new boolean[4096];
        int numSamples = 0;
        boolean hasNonZeroCts = false;

        long[] chunkRelOffsets = new long[512];
        int[] chunkSampleCounts = new int[512];
        int numChunks = 0;
        long totalDurationTicks = 0L;

        ClassicTrackBuilder(byte[] init, int newTrackId, boolean isVideoHint, long fallbackDurationMs) throws IOException {
            this.newTrackId = newTrackId;
            this.fallbackDurationMs = fallbackDurationMs;
            byte[] moov = null;
            for (Box b : boxes(init, 0, init.length)) {
                if ("moov".equals(b.type)) {
                    moov = slice(init, b.start, b.size);
                    break;
                }
            }
            if (moov == null) throw new IOException("DASH init segment has no moov box");

            List<Box> moovKids = boxes(moov, 8, moov.length);
            Box mvhd = findBox(moovKids, "mvhd");
            Box trak = findBox(moovKids, "trak");
            if (mvhd == null || trak == null) throw new IOException("DASH moov missing mvhd/trak");
            this.mvhdBox = slice(moov, mvhd.start, mvhd.size);

            Box mvex = findBox(moovKids, "mvex");
            if (mvex != null) {
                for (Box b : boxes(moov, mvex.contentStart, mvex.contentEnd)) {
                    if ("trex".equals(b.type) && b.contentStart + 24 <= moov.length) {
                        this.trexDefDuration = (int) u32(moov, b.contentStart + 12);
                        this.trexDefSize = (int) u32(moov, b.contentStart + 16);
                        this.trexDefFlags = (int) u32(moov, b.contentStart + 20);
                        break;
                    }
                }
            }

            byte[] trakBytes = slice(moov, trak.start, trak.size);
            this.timescale = timescaleOf(trakBytes);
            List<Box> trakKids = boxes(trakBytes, 8, trakBytes.length);
            Box tkhd = findBox(trakKids, "tkhd");
            Box mdia = findBox(trakKids, "mdia");
            if (tkhd == null || mdia == null) throw new IOException("DASH trak missing tkhd/mdia");
            this.tkhdBox = slice(trakBytes, tkhd.start, tkhd.size);

            List<Box> mdiaKids = boxes(trakBytes, mdia.contentStart, mdia.contentEnd);
            Box mdhd = findBox(mdiaKids, "mdhd");
            Box hdlr = findBox(mdiaKids, "hdlr");
            Box minf = findBox(mdiaKids, "minf");
            if (mdhd == null || hdlr == null || minf == null) throw new IOException("DASH mdia missing mdhd/hdlr/minf");
            this.mdhdBox = slice(trakBytes, mdhd.start, mdhd.size);
            this.hdlrBox = slice(trakBytes, hdlr.start, hdlr.size);

            boolean detectedVideo = isVideoHint;
            if (hdlr.contentStart + 12 <= trakBytes.length) {
                String hType = new String(trakBytes, hdlr.contentStart + 8, 4);
                if ("vide".equals(hType)) detectedVideo = true;
                else if ("soun".equals(hType)) detectedVideo = false;
            }
            this.isVideo = detectedVideo;

            List<Box> minfKids = boxes(trakBytes, minf.contentStart, minf.contentEnd);
            for (Box mk : minfKids) {
                if ("vmhd".equals(mk.type) || "smhd".equals(mk.type) || "hmhd".equals(mk.type) || "nmhd".equals(mk.type)) {
                    this.mediaHeaderBox = slice(trakBytes, mk.start, mk.size);
                } else if ("dinf".equals(mk.type)) {
                    this.dinfBox = slice(trakBytes, mk.start, mk.size);
                } else if ("stbl".equals(mk.type)) {
                    List<Box> stblKids = boxes(trakBytes, mk.contentStart, mk.contentEnd);
                    Box stsd = findBox(stblKids, "stsd");
                    if (stsd != null) this.stsdBox = slice(trakBytes, stsd.start, stsd.size);
                }
            }
            if (this.stsdBox == null) throw new IOException("DASH stbl missing stsd");
            if (this.mediaHeaderBox == null) {
                if (this.isVideo) {
                    byte[] vm = new byte[12];
                    vm[3] = 1;
                    this.mediaHeaderBox = box("vmhd", vm);
                } else {
                    this.mediaHeaderBox = box("smhd", new byte[8]);
                }
            }
            if (this.dinfBox == null) {
                byte[] urlBox = box("url ", new byte[]{0, 0, 0, 1});
                byte[] drefPayload = new byte[8 + urlBox.length];
                put32(drefPayload, 4, 1);
                System.arraycopy(urlBox, 0, drefPayload, 8, urlBox.length);
                this.dinfBox = box("dinf", box("dref", drefPayload));
            }
        }

        int sampleCount() {
            return numSamples;
        }

        private void ensureSampleCapacity(int add) {
            if (numSamples + add <= sampleDurations.length) return;
            int nextCap = Math.max(sampleDurations.length * 2, numSamples + add + 1024);
            int[] nd = new int[nextCap];
            int[] ns = new int[nextCap];
            int[] nc = new int[nextCap];
            boolean[] nsync = new boolean[nextCap];
            System.arraycopy(sampleDurations, 0, nd, 0, numSamples);
            System.arraycopy(sampleSizes, 0, ns, 0, numSamples);
            System.arraycopy(sampleCts, 0, nc, 0, numSamples);
            System.arraycopy(sampleSync, 0, nsync, 0, numSamples);
            sampleDurations = nd;
            sampleSizes = ns;
            sampleCts = nc;
            sampleSync = nsync;
        }

        private void addChunk(long relOffset, int count) {
            if (count <= 0) return;
            if (numChunks >= chunkRelOffsets.length) {
                int nextCap = chunkRelOffsets.length * 2;
                long[] no = new long[nextCap];
                int[] nc = new int[nextCap];
                System.arraycopy(chunkRelOffsets, 0, no, 0, numChunks);
                System.arraycopy(chunkSampleCounts, 0, nc, 0, numChunks);
                chunkRelOffsets = no;
                chunkSampleCounts = nc;
            }
            chunkRelOffsets[numChunks] = relOffset;
            chunkSampleCounts[numChunks] = count;
            numChunks++;
        }

        void consumeFragment(Fragment f, RandomAccessFile spool) throws IOException {
            Box mdatBox = null;
            for (Box b : boxes(f.mdat, 0, f.mdat.length)) {
                if ("mdat".equals(b.type)) {
                    mdatBox = b;
                    break;
                }
            }
            if (mdatBox == null || mdatBox.contentLen() <= 0) return;

            int samplesInFrag = 0;
            int expectedBytesInFrag = 0;
            int firstDataOffset = -1;

            for (Box top : boxes(f.moof, 8, f.moof.length)) {
                if (!"traf".equals(top.type)) continue;
                int defDur = trexDefDuration;
                int defSize = trexDefSize;
                int defFlags = trexDefFlags;
                boolean hasTfhdFlags = false;

                List<Box> trafKids = boxes(f.moof, top.contentStart, top.contentEnd);
                Box tfhd = findBox(trafKids, "tfhd");
                if (tfhd != null && tfhd.contentStart + 8 <= tfhd.contentEnd) {
                    int tfhdFlags = ((f.moof[tfhd.contentStart + 1] & 0xFF) << 16)
                            | ((f.moof[tfhd.contentStart + 2] & 0xFF) << 8)
                            | (f.moof[tfhd.contentStart + 3] & 0xFF);
                    int p = tfhd.contentStart + 8;
                    if ((tfhdFlags & 0x000001) != 0) p += 8;
                    if ((tfhdFlags & 0x000002) != 0) p += 4;
                    if ((tfhdFlags & 0x000008) != 0 && p + 4 <= tfhd.contentEnd) {
                        defDur = (int) u32(f.moof, p);
                        p += 4;
                    }
                    if ((tfhdFlags & 0x000010) != 0 && p + 4 <= tfhd.contentEnd) {
                        defSize = (int) u32(f.moof, p);
                        p += 4;
                    }
                    if ((tfhdFlags & 0x000020) != 0 && p + 4 <= tfhd.contentEnd) {
                        defFlags = (int) u32(f.moof, p);
                        hasTfhdFlags = true;
                    }
                }

                for (Box tb : trafKids) {
                    if (!"trun".equals(tb.type) || tb.contentStart + 8 > tb.contentEnd) continue;
                    int trunFlags = ((f.moof[tb.contentStart + 1] & 0xFF) << 16)
                            | ((f.moof[tb.contentStart + 2] & 0xFF) << 8)
                            | (f.moof[tb.contentStart + 3] & 0xFF);
                    int sCount = (int) u32(f.moof, tb.contentStart + 4);
                    if (sCount <= 0 || sCount > 200000) continue;
                    int p = tb.contentStart + 8;
                    if ((trunFlags & 0x000001) != 0 && p + 4 <= tb.contentEnd) {
                        int dOff = (int) u32(f.moof, p);
                        if (firstDataOffset < 0) firstDataOffset = dOff;
                        p += 4;
                    }
                    int firstSampleFlags = defFlags;
                    boolean hasFirstFlags = false;
                    if ((trunFlags & 0x000004) != 0 && p + 4 <= tb.contentEnd) {
                        firstSampleFlags = (int) u32(f.moof, p);
                        hasFirstFlags = true;
                        p += 4;
                    }

                    ensureSampleCapacity(sCount);
                    for (int i = 0; i < sCount; i++) {
                        int dur = defDur;
                        int sz = defSize;
                        int flg = (i == 0 && hasFirstFlags) ? firstSampleFlags : defFlags;
                        int cts = 0;
                        if ((trunFlags & 0x000100) != 0 && p + 4 <= tb.contentEnd) {
                            dur = (int) u32(f.moof, p);
                            p += 4;
                        }
                        if ((trunFlags & 0x000200) != 0 && p + 4 <= tb.contentEnd) {
                            sz = (int) u32(f.moof, p);
                            p += 4;
                        }
                        if ((trunFlags & 0x000400) != 0 && p + 4 <= tb.contentEnd) {
                            flg = (int) u32(f.moof, p);
                            p += 4;
                        }
                        if ((trunFlags & 0x000800) != 0 && p + 4 <= tb.contentEnd) {
                            cts = (int) u32(f.moof, p);
                            p += 4;
                        }
                        if (dur <= 0) {
                            dur = isVideo ? Math.max(1, timescale / 30) : 1024;
                        }
                        if (sz <= 0 && sCount == 1) {
                            sz = mdatBox.contentLen();
                        }
                        if (sz <= 0) continue;

                        boolean sync;
                        if (!isVideo) {
                            sync = true;
                        } else if ((trunFlags & 0x000400) != 0 || (i == 0 && hasFirstFlags) || hasTfhdFlags) {
                            sync = ((flg & 0x00010000) == 0) && (((flg >>> 24) & 0x03) != 1);
                            if (i == 0 && samplesInFrag == 0 && !sync && (trunFlags & 0x000400) == 0 && !hasFirstFlags) {
                                sync = true;
                            }
                        } else {
                            sync = (i == 0);
                        }

                        sampleDurations[numSamples] = dur;
                        sampleSizes[numSamples] = sz;
                        sampleCts[numSamples] = cts;
                        sampleSync[numSamples] = sync;
                        if (cts != 0) hasNonZeroCts = true;
                        totalDurationTicks += (long) dur;
                        numSamples++;
                        samplesInFrag++;
                        expectedBytesInFrag += sz;
                    }
                }
            }

            if (samplesInFrag <= 0 || expectedBytesInFrag <= 0) return;

            int payloadStart = mdatBox.contentStart;
            if (firstDataOffset > f.moof.length) {
                int rel = firstDataOffset - f.moof.length;
                if (rel >= mdatBox.contentStart && rel + expectedBytesInFrag <= mdatBox.contentEnd) {
                    payloadStart = rel;
                }
            }
            int avail = mdatBox.contentEnd - payloadStart;
            int toWrite = Math.min(avail, expectedBytesInFrag);
            if (toWrite <= 0) return;

            long chunkRelOffset = spool.getFilePointer();
            spool.write(f.mdat, payloadStart, toWrite);
            if (toWrite < expectedBytesInFrag) {
                spool.write(new byte[expectedBytesInFrag - toWrite]);
            }
            addChunk(chunkRelOffset, samplesInFrag);
        }

        long durationMs() {
            if (timescale > 0 && totalDurationTicks > 0L) {
                return (totalDurationTicks * 1000L) / (long) timescale;
            }
            return fallbackDurationMs;
        }

        byte[] buildClassicTrak(long mdatDataFileOffset, long mvhdTimescale) {
            byte[] stts = buildStts();
            byte[] ctts = (isVideo && hasNonZeroCts) ? buildCtts() : null;
            byte[] stss = isVideo ? buildStss() : null;
            byte[] stsc = buildStsc();
            byte[] stsz = buildStsz();
            byte[] stco = buildStco(mdatDataFileOffset);

            List<byte[]> stblKids = new ArrayList<byte[]>();
            stblKids.add(stsdBox);
            stblKids.add(stts);
            if (ctts != null) stblKids.add(ctts);
            if (stss != null) stblKids.add(stss);
            stblKids.add(stsc);
            stblKids.add(stsz);
            stblKids.add(stco);
            byte[] stbl = box("stbl", concat(stblKids));

            List<byte[]> minfKids = new ArrayList<byte[]>();
            minfKids.add(mediaHeaderBox);
            minfKids.add(dinfBox);
            minfKids.add(stbl);
            byte[] minf = box("minf", concat(minfKids));

            byte[] mdhd = mdhdBox.clone();
            patchMdhdDuration(mdhd, totalDurationTicks);
            List<byte[]> mdiaKids = new ArrayList<byte[]>();
            mdiaKids.add(mdhd);
            mdiaKids.add(hdlrBox);
            mdiaKids.add(minf);
            byte[] mdia = box("mdia", concat(mdiaKids));

            byte[] tkhd = tkhdBox.clone();
            // Ensure track_enabled | track_in_movie | track_in_preview (0x000007)
            if (tkhd.length >= 12) {
                tkhd[9] = 0;
                tkhd[10] = 0;
                tkhd[11] = 7;
            }
            byte[] trak = box("trak", concat(java.util.Arrays.asList(tkhd, mdia)));
            patchTrackId(trak, newTrackId);
            patchTkhdDuration(trak, durationMs(), (int) Math.max(1L, mvhdTimescale));
            return trak;
        }

        private byte[] buildStts() {
            int runs = 0;
            for (int i = 0; i < numSamples; i++) {
                if (i == 0 || sampleDurations[i] != sampleDurations[i - 1]) runs++;
            }
            byte[] p = new byte[8 + runs * 8];
            put32(p, 4, runs);
            int pos = 8;
            int i = 0;
            while (i < numSamples) {
                int d = sampleDurations[i];
                int count = 1;
                while (i + count < numSamples && sampleDurations[i + count] == d) count++;
                put32(p, pos, count);
                put32(p, pos + 4, d);
                pos += 8;
                i += count;
            }
            return box("stts", p);
        }

        private byte[] buildCtts() {
            int runs = 0;
            for (int i = 0; i < numSamples; i++) {
                if (i == 0 || sampleCts[i] != sampleCts[i - 1]) runs++;
            }
            byte[] p = new byte[8 + runs * 8];
            put32(p, 4, runs);
            int pos = 8;
            int i = 0;
            while (i < numSamples) {
                int c = sampleCts[i];
                int count = 1;
                while (i + count < numSamples && sampleCts[i + count] == c) count++;
                put32(p, pos, count);
                put32(p, pos + 4, c);
                pos += 8;
                i += count;
            }
            return box("ctts", p);
        }

        private byte[] buildStss() {
            int syncCount = 0;
            for (int i = 0; i < numSamples; i++) {
                if (sampleSync[i]) syncCount++;
            }
            if (syncCount == 0 && numSamples > 0) {
                sampleSync[0] = true;
                syncCount = 1;
            }
            byte[] p = new byte[8 + syncCount * 4];
            put32(p, 4, syncCount);
            int pos = 8;
            for (int i = 0; i < numSamples; i++) {
                if (sampleSync[i]) {
                    put32(p, pos, i + 1);
                    pos += 4;
                }
            }
            return box("stss", p);
        }

        private byte[] buildStsc() {
            int runs = 0;
            for (int c = 0; c < numChunks; c++) {
                if (c == 0 || chunkSampleCounts[c] != chunkSampleCounts[c - 1]) runs++;
            }
            byte[] p = new byte[8 + runs * 12];
            put32(p, 4, runs);
            int pos = 8;
            for (int c = 0; c < numChunks; c++) {
                if (c == 0 || chunkSampleCounts[c] != chunkSampleCounts[c - 1]) {
                    put32(p, pos, c + 1);
                    put32(p, pos + 4, chunkSampleCounts[c]);
                    put32(p, pos + 8, 1);
                    pos += 12;
                }
            }
            return box("stsc", p);
        }

        private byte[] buildStsz() {
            byte[] p = new byte[12 + numSamples * 4];
            put32(p, 4, 0);
            put32(p, 8, numSamples);
            int pos = 12;
            for (int i = 0; i < numSamples; i++) {
                put32(p, pos, sampleSizes[i]);
                pos += 4;
            }
            return box("stsz", p);
        }

        private byte[] buildStco(long mdatDataFileOffset) {
            byte[] p = new byte[8 + numChunks * 4];
            put32(p, 4, numChunks);
            int pos = 8;
            for (int c = 0; c < numChunks; c++) {
                put32(p, pos, mdatDataFileOffset + chunkRelOffsets[c]);
                pos += 4;
            }
            return box("stco", p);
        }
    }

    static void patchMdhdDuration(byte[] mdhd, long durationTicks) {
        if (mdhd.length < 24 || durationTicks <= 0L) return;
        int version = mdhd[8] & 0xFF;
        if (version == 1) {
            if (mdhd.length >= 36) put64(mdhd, 28, durationTicks);
        } else {
            if (mdhd.length >= 24) put32(mdhd, 20, durationTicks);
        }
    }

    static long mvhdTimescaleOf(byte[] mvhd) {
        if (mvhd == null || mvhd.length < 24) return 1000L;
        int version = mvhd[8] & 0xFF;
        int tsOff = 8 + (version == 1 ? 20 : 12);
        if (tsOff + 4 > mvhd.length) return 1000L;
        long ts = u32(mvhd, tsOff);
        return ts > 0L ? ts : 1000L;
    }

    static byte[] buildClassicMoov(byte[] mvhdTemplate,
                                   List<ClassicTrackBuilder> tracks,
                                   long mdatDataFileOffset) {
        byte[] mvhd = mvhdTemplate.clone();
        long mvhdTs = mvhdTimescaleOf(mvhd);
        List<byte[]> kids = new ArrayList<byte[]>();
        kids.add(mvhd);
        long maxDurMs = 0L;
        int maxTrackId = 1;
        for (int i = 0; i < tracks.size(); i++) {
            ClassicTrackBuilder tb = tracks.get(i);
            if (tb.durationMs() > maxDurMs) maxDurMs = tb.durationMs();
            if (tb.newTrackId > maxTrackId) maxTrackId = tb.newTrackId;
            kids.add(tb.buildClassicTrak(mdatDataFileOffset, mvhdTs));
        }
        byte[] moov = box("moov", concat(kids));
        patchMvhd(moov, maxDurMs, maxTrackId + 1, true);
        return moov;
    }

    /* ======================================================================
     * 6. Small helpers used by the JVM regression suite
     * ====================================================================== */

    /** Reads exactly `bytes` bytes from a stream (used by tests and HTTP readers). */
    public static byte[] readFully(java.io.InputStream in, int bytes) throws IOException {
        byte[] out = new byte[bytes];
        int off = 0;
        while (off < bytes) {
            int n = in.read(out, off, bytes - off);
            if (n < 0) throw new EOFException("short read: " + off + "/" + bytes);
            off += n;
        }
        return out;
    }
}
