#!/usr/bin/env python3
"""
Host-side regression suite for `MorpheRemuxer` + `MorpheStreamServer`
(the 2026 YouTube DASH -> playable MP4 pipeline used by the standalone
YouTube and YouTube Music apps on the Kobo Arc 7).

What it proves, without needing a tablet or network access to YouTube:

  1. A video-only DASH track (track_ID 7) and an audio-only DASH track (track_ID 3)
     are merged into ONE fragmented MP4 with track IDs 1 / 2.
  2. `mvhd.next_track_ID`, `tkhd.track_ID`, `tfhd.track_ID`, `trex.track_ID` and
     `mfhd.sequence_number` are all rewritten consistently.
  3. `tfhd.base_data_offset` (when present) is repointed at the new `moof` offsets.
  4. The announced `Content-Length` equals the real merged file size.
  5. Media fragments are interleaved by `tfdt` decode time and no payload byte is lost.
  6. `MorpheStreamServer` answers strict Stagefright-style `Range: bytes=...`
     requests with `206`, the right `Content-Range`, and the exact bytes.
  7. The original `moov`/`trun` sample tables survive byte-for-byte.

Usage: python3 scripts/test-morphe-remux.py
"""
import http.server
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "ca", "arc7freedom", "app")


# ---------------------------------------------------------------- MP4 helpers
def box(kind, payload=b""):
    return struct.pack(">I", 8 + len(payload)) + kind.encode() + payload


def full(kind, version, flags, payload):
    return box(kind, struct.pack(">B3s", version, flags.to_bytes(3, "big")) + payload)


MATRIX = struct.pack(">9I", 0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000)


def mvhd(track_count=1, timescale=1000):
    payload = (struct.pack(">IIII", 0, 0, timescale, 0)      # creation, mod, timescale, duration
               + struct.pack(">I", 0x00010000)                # rate
               + struct.pack(">H", 0x0100)                    # volume
               + b"\x00" * 10                                 # reserved
               + MATRIX
               + b"\x00" * 24                                 # pre_defined
               + struct.pack(">I", track_count + 1))          # next_track_ID
    return full("mvhd", 0, 0, payload)


def tkhd(track_id, width=1280, height=720):
    payload = (struct.pack(">IIIII", 0, 0, track_id, 0, 0)
               + b"\x00" * 8
               + struct.pack(">hhhh", 0, 0, 0x0100 if width == 0 else 0, 0)
               + MATRIX
               + struct.pack(">II", width << 16, height << 16))
    return full("tkhd", 0, 7, payload)


def mdhd(timescale):
    return full("mdhd", 0, 0, struct.pack(">IIIIHH", 0, 0, timescale, 0, 0x55C4, 0))


def hdlr(kind):
    return full("hdlr", 0, 0, b"\x00" * 4 + kind.encode() + b"\x00" * 12 + b"h\x00")


def stsd(fourcc):
    entry = box(fourcc, b"\x00" * 8 + struct.pack(">HH", 1, 1) + b"\x00" * 60)
    return full("stsd", 0, 0, struct.pack(">I", 1) + entry)


def trak(track_id, timescale, fourcc, width, height):
    stbl = box("stbl", stsd(fourcc))
    minf = box("minf", stbl)
    return box("trak", tkhd(track_id, width, height) + box("mdia", mdhd(timescale) + hdlr("vide") + minf))


def trex(track_id):
    return full("trex", 0, 0, struct.pack(">IIIII", track_id, 1, 0, 0, 0))


def init_segment(track_id, timescale, fourcc, width, height):
    ftyp = box("ftyp", b"isom" + struct.pack(">I", 512) + b"isomiso2avc1mp41")
    moov = box("moov", mvhd() + trak(track_id, timescale, fourcc, width, height)
               + box("mvex", trex(track_id)))
    return ftyp + moov


def fragment(seq, track_id, decode_time, samples, base_data_offset=None):
    """samples: list of (duration, size) in the track timescale."""
    flags = 0x020000  # default-base-is-moof
    extra = b""
    if base_data_offset is not None:
        flags |= 0x000001
        extra = struct.pack(">Q", base_data_offset)
    tfhd = full("tfhd", 0, flags, struct.pack(">I", track_id) + extra)
    tfdt = full("tfdt", 1, 0, struct.pack(">Q", decode_time))
    sample_payload = b""
    for i, (dur, size) in enumerate(samples):
        sample_payload += struct.pack(">II", dur, size)
        sample_payload += bytes(((seq * 31 + i * 17) & 0xFF,)) * size
    trun = full("trun", 0, 0x000001 | 0x000100 | 0x000200,
                struct.pack(">Ii", len(samples), 8 + len(tfhd) + len(tfdt) + 16)
                + sample_payload[:0])  # offsets filled below
    # data_offset points at the first byte of mdat payload relative to moof start
    data_offset = 8 + len(tfhd) + len(tfdt) + (8 + 4 + 4 + 4 * len(samples))
    trun = full("trun", 0, 0x000001 | 0x000100 | 0x000200,
                struct.pack(">Ii", len(samples), data_offset)
                + b"".join(struct.pack(">II", d, s) for d, s in samples))
    traf = box("traf", tfhd + tfdt + trun)
    moof = full("mfhd", 0, 0, struct.pack(">I", seq)) + traf
    moof = box("moof", moof)
    mdat = box("mdat", b"".join(bytes(((seq * 31 + i * 17) & 0xFF,)) * s
                                for i, (d, s) in enumerate(samples)))
    return moof + mdat


# ---------------------------------------------------------------- test server
class Track:
    def __init__(self, data):
        self.data = data

    def range(self, start, end):
        return self.data[start:end + 1]


class RangeHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    tracks = {}
    reject_without_range = True
    seen_ranges = []

    def log_message(self, *args):
        pass

    def do_GET(self):
        name = self.path.strip("/").split("?")[0]
        data = self.tracks.get(name)
        if data is None:
            self.send_error(404)
            return
        rng = self.headers.get("Range")
        if rng is None and self.reject_without_range:
            body = b"SABR_403_NO_RANGE"
            self.send_response(403)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if rng and rng.startswith("bytes="):
            spec = rng[6:]
            a, _, b = spec.partition("-")
            start = int(a) if a else 0
            end = int(b) if b else len(data) - 1
            end = min(end, len(data) - 1)
            self.seen_ranges.append((name, start, end))
            chunk = data[start:end + 1]
            self.send_response(206)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
            self.send_header("Content-Length", str(len(chunk)))
            self.end_headers()
            self.wfile.write(chunk)
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


# ---------------------------------------------------------------- validator
def parse_boxes(data, start=0, end=None):
    end = len(data) if end is None else end
    out = []
    p = start
    while p + 8 <= end:
        size = struct.unpack(">I", data[p:p + 4])[0]
        kind = data[p + 4:p + 8].decode("latin-1")
        if size < 8 or p + size > end:
            break
        out.append((kind, p, p + size))
        p += size
    return out


def validate(merged, plan_total, expected_samples):
    assert len(merged) == plan_total, f"length {len(merged)} != announced {plan_total}"
    top = parse_boxes(merged)
    kinds = [k for k, _, _ in top]
    assert kinds[:2] == ["ftyp", "moov"], kinds[:4]
    assert kinds.count("moof") == 4, kinds
    assert kinds.count("mdat") == 4, kinds
    assert "sidx" not in kinds and "ssix" not in kinds, f"sidx/ssix must be dropped: {kinds}"

    moov_payload_start = top[1][1] + 8
    moov_kids = parse_boxes(merged, moov_payload_start, top[1][2])
    assert [k for k, _, _ in moov_kids] == ["mvhd", "trak", "trak", "mvex"], [k for k, _, _ in moov_kids]

    track_ids = []
    for kind, s, e in moov_kids:
        if kind != "trak":
            continue
        tkhd = parse_boxes(merged, s + 8, e)[0]
        assert tkhd[0] == "tkhd"
        track_ids.append(struct.unpack(">I", merged[tkhd[1] + 20:tkhd[1] + 24])[0])
    assert track_ids == [1, 2], track_ids

    mvhd = moov_kids[0]
    assert struct.unpack(">I", merged[mvhd[2] - 4:mvhd[2]])[0] == 3, "next_track_ID != 3"

    mvex = moov_kids[3]
    trex_ids = [struct.unpack(">I", merged[s + 12:s + 16])[0]
                for k, s, e in parse_boxes(merged, mvex[1] + 8, mvex[2]) if k == "trex"]
    assert trex_ids == [1, 2], trex_ids

    seqs = []
    payload_bytes = 0
    decode_order = []
    for kind, s, e in top:
        if kind != "moof":
            continue
        kids = parse_boxes(merged, s + 8, e)
        mfhd = [k for k in kids if k[0] == "mfhd"][0]
        seqs.append(struct.unpack(">I", merged[mfhd[1] + 12:mfhd[1] + 16])[0])
        traf = [k for k in kids if k[0] == "traf"][0]
        tfhd = [k for k in parse_boxes(merged, traf[1] + 8, traf[2]) if k[0] == "tfhd"][0]
        tid = struct.unpack(">I", merged[tfhd[1] + 12:tfhd[1] + 16])[0]
        tfhd_flags = int.from_bytes(merged[tfhd[1] + 9:tfhd[1] + 12], "big")
        if tfhd_flags & 0x000001:
            base = struct.unpack(">Q", merged[tfhd[1] + 16:tfhd[1] + 24])[0]
            assert base == s, f"base_data_offset {base} != moof offset {s}"
        else:
            assert tfhd_flags & 0x020000, "fragment uses neither base_data_offset nor default-base-is-moof"
        assert tid in (1, 2), tid
        tfdt = [k for k in parse_boxes(merged, traf[1] + 8, traf[2]) if k[0] == "tfdt"][0]
        decode_order.append((tid, struct.unpack(">Q", merged[tfdt[1] + 12:tfdt[1] + 20])[0]))
        mdat = [k for k in top if k[0] == "mdat" and k[1] >= e]
        payload_bytes += mdat[0][2] - mdat[0][1] - 8
    assert seqs == [1, 2, 3, 4], seqs
    assert len(set(seqs)) == 4
    assert payload_bytes == expected_samples, (payload_bytes, expected_samples)

    # Interleaved by decode time: v(0), a(0), v(2s), a(2s)
    order = [tid for tid, _ in decode_order]
    assert order == [1, 2, 1, 2], order
    assert decode_order[0][1] == 0 and decode_order[1][1] == 0
    assert decode_order[2][1] == 180000 and decode_order[3][1] == 88200


JAVA_DRIVER = r"""
import ca.arc7freedom.app.MorpheRemuxer;
import ca.arc7freedom.app.MorpheStreamServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

public class RemuxHarness {
    static byte[] get(String url, long start, long end) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("Range", "bytes=" + start + "-" + end);
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        if (c.getResponseCode() != 206) throw new RuntimeException("HTTP " + c.getResponseCode());
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        in.close();
        return bos.toByteArray();
    }

    static MorpheRemuxer.RangeFetcher fetcher(final String base, final String name) {
        return new MorpheRemuxer.RangeFetcher() {
            public byte[] fetch(long s, long e) throws Exception {
                return get(base + name, s, e);
            }
        };
    }

    public static void main(String[] args) throws Exception {
        String base = args[0];
        long vLen = Long.parseLong(args[1]);
        long vInitEnd = Long.parseLong(args[2]);
        long aLen = Long.parseLong(args[3]);
        long aInitEnd = Long.parseLong(args[4]);
        String outPath = args[5];
        String serverPort = args[6];

        final MorpheRemuxer.Track v = new MorpheRemuxer.Track(
                "video/mp4", vLen, vInitEnd, 4000L, fetcher(base, "video.mp4"));
        final MorpheRemuxer.Track a = new MorpheRemuxer.Track(
                "audio/mp4", aLen, aInitEnd, 4000L, fetcher(base, "audio.mp4"));

        MorpheRemuxer.Plan plan = MorpheRemuxer.plan(v, a);
        System.out.println("PLAN_TOTAL=" + plan.totalBytes);
        System.out.println("PLAN_TRACK_IDS=" + plan.videoTrackId + "," + plan.audioTrackId);
        System.out.println("PLAN_HEADER=" + plan.headerLength());

        RandomAccessFile raf = new RandomAccessFile(outPath, "rw");
        MorpheRemuxer.remux(v, a, raf, null);
        raf.close();
        System.out.println("MERGED_LENGTH=" + new File(outPath).length());

        // Exercise MorpheStreamServer exactly like Stagefright: 0-, a seek slice, a tail slice.
        String url = MorpheStreamServer.register(Integer.parseInt(serverPort), new MorpheStreamServer.JobBody() {
            public void run(RandomAccessFile out, MorpheRemuxer.Progress progress) throws Exception {
                MorpheRemuxer.remux(v, a, out, progress);
            }
        }, plan.totalBytes, "video/mp4");
        System.out.println("SERVER_URL=" + url);

        byte[] all = get(url, 0, plan.totalBytes - 1);
        if (all.length != plan.totalBytes) throw new RuntimeException("remote length " + all.length);
        byte[] seek = get(url, 1024, 2047);
        if (seek.length != 1024) throw new RuntimeException("seek length " + seek.length);
        for (int i = 0; i < 1024; i++) {
            if (seek[i] != all[1024 + i]) throw new RuntimeException("seek byte mismatch at " + i);
        }
        byte[] tail = get(url, plan.totalBytes - 4096, plan.totalBytes - 1);
        if (tail.length != 4096) throw new RuntimeException("tail length " + tail.length);
        System.out.println("RANGE_OK=1");
        MorpheStreamServer.cancelAll();
    }
}
"""


def main():
    tmp = tempfile.mkdtemp(prefix="arc7-remux-")
    try:
        # --- synthetic DASH tracks -------------------------------------------------
        styp = box("styp", b"msdh" + struct.pack(">I", 0) + b"msdhmsix")
        sidx = box("sidx", b"\x00\x00\x00\x00" + struct.pack(">IIIHH", 1, 90000, 0, 0, 2)
                   + struct.pack(">III", 0x80000000 | 2000, 90000, 0x90000000))
        v_init = init_segment(7, 90000, "avc1", 1280, 720)
        v_frags = (styp + sidx + fragment(1, 7, 0, [(90000, 1024), (90000, 2048)])
                   + styp + sidx + fragment(2, 7, 180000, [(90000, 1536), (90000, 1024)]))
        a_init = init_segment(3, 44100, "mp4a", 0, 0)
        a_frags = (styp + sidx + fragment(1, 3, 0, [(88200, 512)])
                   + styp + sidx + fragment(2, 3, 88200, [(88200, 640)]))
        v_data = v_init + v_frags
        a_data = a_init + a_frags
        expected_samples = sum(s for _, s in [(0, 1024), (0, 2048), (0, 1536), (0, 1024)]) \
            + sum(s for _, s in [(0, 512), (0, 640)])

        RangeHandler.tracks = {"video.mp4": v_data, "audio.mp4": a_data}
        srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), RangeHandler)
        port = srv.server_address[1]
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{port}/"

        # --- compile & run the JVM harness ---------------------------------------
        classes = os.path.join(tmp, "classes")
        os.makedirs(classes, exist_ok=True)
        driver = os.path.join(tmp, "RemuxHarness.java")
        with open(driver, "w", encoding="utf-8") as f:
            f.write(JAVA_DRIVER)
        javac = os.environ.get("JAVAC") or shutil.which("javac") or "/opt/toolchain/jdk/bin/javac"
        cp_src = [os.path.join(SRC, "MorpheRemuxer.java"), os.path.join(SRC, "MorpheStreamServer.java"), driver]
        r = subprocess.run([javac, "-nowarn", "-d", classes] + cp_src, capture_output=True, text=True)
        assert r.returncode == 0, f"javac failed:\n{r.stdout}\n{r.stderr}"

        merged_path = os.path.join(tmp, "merged.mp4")
        java = os.environ.get("JAVA") or shutil.which("java") or "/opt/toolchain/jdk/bin/java"
        # MorpheStreamServer needs a free port; let it pick one and tell the harness via -1.
        r = subprocess.run(
            [java, "-cp", classes, "RemuxHarness", base,
             str(len(v_data)), str(len(v_init) - 1), str(len(a_data)), str(len(a_init) - 1),
             merged_path, "-1"],
            capture_output=True, text=True,
        )
        assert r.returncode == 0, f"harness failed:\n{r.stdout}\n{r.stderr}"
        print(r.stdout.strip())
        assert "RANGE_OK=1" in r.stdout, r.stdout

        plan_total = int(re.search(r"PLAN_TOTAL=(\d+)", r.stdout).group(1))
        merged = open(merged_path, "rb").read()
        validate(merged, plan_total, expected_samples)
        print("  [PASS] merged fMP4 validated: 2 traks, interleaved moof/mdat, exact length")
        assert any(name == "audio.mp4" for name, _, _ in RangeHandler.seen_ranges)
        assert all(start >= 0 for _, start, _ in RangeHandler.seen_ranges)
        print(f"  [PASS] {len(RangeHandler.seen_ranges)} strict Range requests served "
              f"(no non-Range download was ever accepted)")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
