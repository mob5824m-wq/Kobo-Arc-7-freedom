package ca.arc7freedom.app;

import java.io.IOException;

/** Bounded PAT/PMT inspection; a TS sync byte alone does not prove that audio exists. */
public final class LegacyTsProbe {
    private static final int PACKET = 188;
    private LegacyTsProbe() {}

    public static final class Tracks {
        public boolean video;
        public boolean audio;
    }

    /** Inspect the first program only, as the legacy Android TS extractor does. */
    public static Tracks tracks(byte[] data) throws IOException {
        if (data == null || data.length < PACKET * 2 || data[0] != 0x47 || data[PACKET] != 0x47)
            throw new IOException("Not an MPEG-TS stream");
        Section pat = new Section(), pmt = new Section();
        int pmtPid = -1;
        for (int offset = 0; offset + PACKET <= data.length; offset += PACKET) {
            if (data[offset] != 0x47 || (data[offset + 1] & 0x80) != 0)
                throw new IOException("Invalid MPEG-TS packet");
            int pid = ((data[offset + 1] & 31) << 8) | (data[offset + 2] & 255);
            if (pid != 0 && pid != pmtPid) continue;
            if ((data[offset + 3] & 0xc0) != 0) throw new IOException("Scrambled MPEG-TS tables");
            int control = (data[offset + 3] >>> 4) & 3;
            if (control == 0) throw new IOException("Invalid MPEG-TS adaptation control");
            if ((control & 1) == 0) continue;
            int start = offset + 4;
            if ((control & 2) != 0) start += 1 + (data[start] & 255);
            if (start >= offset + PACKET) continue;
            boolean unitStart = (data[offset + 1] & 0x40) != 0;
            byte[] section = (pid == 0 ? pat : pmt).add(data, start, offset + PACKET, unitStart);
            if (section == null) continue;
            if (pid == 0) {
                if (section[0] != 0 || section.length < 12 || (section[5] & 1) == 0) continue;
                for (int i = 8; i + 4 <= section.length - 4; i += 4) {
                    if (section[i] == 0 && section[i + 1] == 0) continue; // network PID
                    pmtPid = ((section[i + 2] & 31) << 8) | (section[i + 3] & 255);
                    break;
                }
            } else {
                if (section[0] != 2 || section.length < 16 || (section[5] & 1) == 0) continue;
                Tracks tracks = new Tracks();
                int programInfo = ((section[10] & 15) << 8) | (section[11] & 255);
                int i = 12 + programInfo, end = section.length - 4;
                if (i > end) throw new IOException("Truncated MPEG-TS program descriptors");
                while (i < end) {
                    if (i + 5 > end) throw new IOException("Truncated MPEG-TS stream table");
                    int type = section[i] & 255;
                    int descriptors = ((section[i + 3] & 15) << 8) | (section[i + 4] & 255);
                    if (i + 5 + descriptors > end) throw new IOException("Truncated MPEG-TS stream descriptors");
                    if (type == 0x1b) tracks.video = true; // H.264
                    if (type == 0x0f) tracks.audio = true; // AAC ADTS, not LATM/Opus/AC3
                    i += 5 + descriptors;
                }
                return tracks;
            }
        }
        throw new IOException("MPEG-TS program/audio table was not received");
    }

    /** A PSI section can span packets; never scan arbitrary PES bytes for a fake PMT. */
    private static final class Section {
        private final byte[] bytes = new byte[1024];
        private int length, expected = -1;

        byte[] add(byte[] data, int start, int end, boolean unitStart) throws IOException {
            if (unitStart) {
                int pointer = data[start++] & 255;
                // Finish an existing section before the new section's pointer boundary.
                if (pointer > end - start) throw new IOException("Invalid MPEG-TS PSI pointer");
                if (length > 0) {
                    byte[] finished = append(data, start, start + pointer);
                    if (finished != null) return finished;
                }
                length = 0; expected = -1; start += pointer;
            } else if (length == 0) return null;
            return append(data, start, end);
        }

        private byte[] append(byte[] data, int start, int end) throws IOException {
            while (start < end) {
                if (length == 0 && (data[start] & 255) == 255) return null; // stuffing
                if (length == bytes.length) throw new IOException("MPEG-TS PSI section exceeds limit");
                bytes[length++] = data[start++];
                if (length == 3) {
                    expected = 3 + (((bytes[1] & 15) << 8) | (bytes[2] & 255));
                    if (expected < 8 || expected > bytes.length) throw new IOException("Invalid MPEG-TS PSI size");
                }
                if (length == expected) {
                    byte[] result = new byte[length];
                    System.arraycopy(bytes, 0, result, 0, length);
                    length = 0; expected = -1;
                    if (crc(result) != 0) throw new IOException("Invalid MPEG-TS table checksum");
                    return result;
                }
            }
            return null;
        }
    }

    private static int crc(byte[] data) {
        int value = -1;
        for (int i = 0; i < data.length; i++) {
            value ^= (data[i] & 255) << 24;
            for (int bit = 0; bit < 8; bit++) value = (value << 1) ^ (value < 0 ? 0x04c11db7 : 0);
        }
        return value;
    }
}
