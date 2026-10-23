#!/usr/bin/env bash
# ==============================================================================
# Kobo Arc 7 Freedom v2.5.2 — 1-Click macOS / Linux Wi-Fi OTA Rooting Server (:8000)
#
# Concurrent HTTP/1.1 + inline PNG/JPEG/SVG image serving + `Range: bytes=...`
# (`206 Partial Content`) + instant TLS ClientHello reject so Kobo's Browser &
# Android DownloadManager display all images and stream every `.apk` cleanly!
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -f "${SCRIPT_DIR}/serve-over-wifi.py" ] && [ -f "${SCRIPT_DIR}/../releases/KoboArc7Freedom.apk" ]; then
    exec python3 "${SCRIPT_DIR}/serve-over-wifi.py" "$@"
fi

SERVE_DIR="${SCRIPT_DIR}/Kobo-WiFi-Files"
mkdir -p "${SERVE_DIR}"

RAW_BASE="https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases"
# Some releases are published from a feature branch before they land on `main`; try that too so
# a freshly built v2.5.2 tree is reachable even if the GitHub release is not published yet.
RAW_BRANCH_BASE="https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/arena/fdd69b2f-kobo-arc-7-freedom/releases"
RELEASE_BASE="https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download"

# Main suite files (+ the separately installable YouTube / YouTube Music apps).
# Everything downloads in the BACKGROUND: the portal is already answering on :8000 while
# these copies finish, and a blocked/slow GitHub connection can never stall it.
(
for ASSET in logo-banner.png Upgrade-My-Kobo-To-v2.5.2.apk KoboArc7Freedom.apk YouTube.apk YouTubeMusic.apk VLC.apk AuroraStore.apk FDroidClassicLegacy.apk VanillaAndroid4Home.apk SHA256SUMS.txt; do
    if [ -f "${SCRIPT_DIR}/${ASSET}" ] && [ ! -s "${SERVE_DIR}/${ASSET}" ]; then
        cp -f "${SCRIPT_DIR}/${ASSET}" "${SERVE_DIR}/${ASSET}"
    fi
    if [ ! -s "${SERVE_DIR}/${ASSET}" ]; then
        echo "==> Downloading ${ASSET}..."
        curl -fL --connect-timeout 5 --progress-bar "${RAW_BASE}/${ASSET}" -o "${SERVE_DIR}/${ASSET}" \
            || curl -fL --connect-timeout 5 --progress-bar "${RELEASE_BASE}/${ASSET}" -o "${SERVE_DIR}/${ASSET}" \
            || curl -fL --connect-timeout 5 --progress-bar "${RAW_BRANCH_BASE}/${ASSET}" -o "${SERVE_DIR}/${ASSET}" \
            || true
    fi
done

# The 27 separately installable default apps (Browser, Clock, Calculator, ...).
# Release assets are flat, so they land in `Kobo-WiFi-Files/apps/<Name>.apk`.
if [ -f "${SCRIPT_DIR}/split-apps/apps.tsv" ]; then
    APP_LIST="$(cut -f2 "${SCRIPT_DIR}/split-apps/apps.tsv")"
else
    APP_LIST="Ad-Blocker.apk App-Manager.apk Books.apk Browser.apk Calculator.apk Calendar.apk Camera.apk Clock.apk Files.apk Games.apk Music.apk Notes.apk Package-Installer.apk PDF-Reader.apk Photos.apk Radio-Podcasts.apk Second-Screen.apk Split-View.apk Stream-Deck.apk System-Boost.apk Terminal.apk USB-Internet.apk Voice-Recorder.apk Wallpapers.apk Weather.apk Wi-Fi-VPN.apk Wikipedia.apk"
fi
mkdir -p "${SERVE_DIR}/apps"
APP_COUNT=0
APP_MISSING=0
for ASSET in ${APP_LIST}; do
    DEST="${SERVE_DIR}/apps/${ASSET}"
    if [ -f "${SCRIPT_DIR}/releases/apps/${ASSET}" ] && [ ! -s "${DEST}" ]; then
        cp -f "${SCRIPT_DIR}/releases/apps/${ASSET}" "${DEST}"
    fi
    if [ ! -s "${DEST}" ]; then
        curl -fsSL --connect-timeout 5 "${RELEASE_BASE}/${ASSET}" -o "${DEST}" 2>/dev/null || true
    fi
    if [ -s "${DEST}" ]; then
        APP_COUNT=$((APP_COUNT + 1))
    else
        rm -f "${DEST}"
        APP_MISSING=$((APP_MISSING + 1))
    fi
done
echo "==> Separate apps ready: ${APP_COUNT} local, ${APP_MISSING} will link to GitHub."
) >"${SCRIPT_DIR}/Kobo-WiFi-download.log" 2>&1 &

if [ -f "${SCRIPT_DIR}/serve-over-wifi.py" ]; then
    ARC7_SERVE_DIR="${SERVE_DIR}" exec python3 "${SCRIPT_DIR}/serve-over-wifi.py" "$@"
fi

exec python3 - "${SERVE_DIR}" "${1:-8000}" << 'PYEOF'
import base64, html, http.server, os, re, socket, subprocess, sys, urllib.parse, zlib, struct

SERVE_DIR = sys.argv[1]
PORT = int(sys.argv[2])

IMG_MIMES = {
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".gif": "image/gif",
    ".webp": "image/webp",
    ".svg": "image/svg+xml",
    ".ico": "image/x-icon",
}

def make_fallback_banner_png() -> bytes:
    w, h = 640, 128
    rows = []
    for y in range(h):
        row = bytearray([0])
        for x in range(w):
            row.extend((11 + (x * 24) // w, 28 + (y * 36) // h, 58 + (x * 90) // w, 255))
        rows.append(bytes(row))
    raw = b"".join(rows)
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 6))
        + chunk(b"IEND", b"")
    )

FALLBACK_BANNER = make_fallback_banner_png()

def get_ips():
    out = []
    try:
        r = subprocess.check_output(["hostname", "-I"], text=True, stderr=subprocess.DEVNULL)
        out.extend(x for x in r.split() if "." in x and not x.startswith(("127.", "169.254.")))
    except Exception:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip not in out and not ip.startswith(("127.", "169.254.")):
            out.insert(0, ip)
    except Exception:
        pass
    return out or ["127.0.0.1"]

class H(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def _index_bytes(self):
        items = []
        entries = [(f, os.path.join(SERVE_DIR, f)) for f in sorted(os.listdir(SERVE_DIR))]
        apps_root = os.path.join(SERVE_DIR, "apps")
        if os.path.isdir(apps_root):
            entries += [("apps/" + f, os.path.join(apps_root, f)) for f in sorted(os.listdir(apps_root))]
        for f, fp in entries:
            if not os.path.isfile(fp) or os.path.splitext(f.lower())[1] in IMG_MIMES:
                continue
            mb = os.path.getsize(fp) / 1048576.0
            cls = "btn primary" if f.startswith(("Upgrade-My-Kobo", "KoboArc7Freedom")) else "btn"
            items.append(f"<a class='{cls}' href='./{urllib.parse.quote(f)}'>Download {html.escape(f)} ({mb:.2f} MB)</a>")
            if f.lower().endswith(".apk"):
                items.append(f"<a class='sub' href='./raw/{urllib.parse.quote(f)}'>Alternate Direct Stream ({html.escape(f)})</a>")
        return (
            "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            "<title>Kobo Arc 7 Freedom v2.5.2 — Local Wi-Fi Installer</title>"
            "<style>body{font-family:sans-serif;background:#0e1319;color:#f8fafc;padding:20px;}"
            ".card{background:#161e28;border:1px solid #2b394b;border-radius:10px;padding:20px;max-width:660px;margin:auto;}"
            "h1{color:#38bdf8;font-size:20px;margin-top:0;}.btn{display:block;background:#1f2a38;color:#fff;"
            "text-decoration:none;padding:14px 18px;border-radius:8px;margin:10px 0 4px 0;font-weight:bold;border:1px solid #475569;text-align:center;}"
            ".primary{background:#0284c7;border-color:#7dd3fc;font-size:17px;}"
            ".sub{display:block;color:#93c5fd;text-align:center;font-size:12.5px;margin-bottom:10px;}</style></head><body>"
            "<div style='text-align:center;max-width:660px;margin:0 auto 14px auto;'>"
            "<img src='./logo-banner.png' alt='Kobo Arc 7 Freedom' style='max-width:100%;max-height:112px;border-radius:10px;'>"
            "</div><div class='card'>"
            "<h1>Kobo Arc 7 Freedom v2.5.2 — Wi-Fi OTA Rooting &amp; Installer</h1>"
            "<p>1. Enable <b>Settings &rarr; Security &rarr; Unknown sources</b> on your tablet.<br>"
            "2. Tap <b>Upgrade-My-Kobo-To-v2.5.2.apk</b> (1-tap upgrade) or <b>KoboArc7Freedom.apk</b>.<br>"
            "3. Swipe down the <b>top-left notification shade</b> on your Kobo and tap to install!</p>"
            + "".join(items) + "</div></body></html>"
        ).encode("utf-8")

    def _serve(self, send_body: bool):
        raw_path = urllib.parse.unquote(urllib.parse.urlsplit(self.path).path).lstrip("/")
        force_octet = False
        if raw_path.startswith("raw/"):
            force_octet = True
            raw_path = raw_path[4:]
        fname = os.path.basename(raw_path)
        if fname == "favicon.ico":
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(FALLBACK_BANNER)))
            self.send_header("Connection", "close")
            self.end_headers()
            if send_body:
                self.wfile.write(FALLBACK_BANNER)
            return
        if fname in ("", "index.html"):
            b = self._index_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(b)))
            self.send_header("Connection", "close")
            self.end_headers()
            if send_body:
                self.wfile.write(b)
            return
        fp = os.path.join(SERVE_DIR, fname)
        if not os.path.isfile(fp):
            if fname == "logo-banner.png":
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.send_header("Content-Length", str(len(FALLBACK_BANNER)))
                self.send_header("Connection", "close")
                self.end_headers()
                if send_body:
                    self.wfile.write(FALLBACK_BANNER)
                return
            self.send_error(404)
            return
        total = os.path.getsize(fp)
        start, end = 0, max(0, total - 1)
        code = 200
        rng = self.headers.get("Range", "")
        m = re.match(r"bytes=(\d*)-(\d*)", rng)
        if m and total > 0:
            if m.group(1):
                start = int(m.group(1))
            if m.group(2):
                end = min(int(m.group(2)), total - 1)
            if 0 <= start <= end < total:
                code = 206
        length = (end - start + 1) if total > 0 else 0
        ext = os.path.splitext(fname.lower())[1]
        img_mime = IMG_MIMES.get(ext)
        is_inline = bool(img_mime) and not force_octet
        if force_octet:
            mime = "application/octet-stream"
        elif img_mime:
            mime = img_mime
        elif ext == ".apk":
            mime = "application/vnd.android.package-archive"
        else:
            mime = "text/plain; charset=utf-8"
        self.send_response(code)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
        if not is_inline:
            self.send_header("Content-Disposition", f'attachment; filename="{fname}"')
        self.send_header("Connection", "close")
        self.end_headers()
        if send_body and length > 0:
            with open(fp, "rb") as f:
                f.seek(start)
                rem = length
                while rem > 0:
                    chunk = f.read(min(65536, rem))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    rem -= len(chunk)

    def do_HEAD(self):
        self._serve(False)

    def do_GET(self):
        self._serve(True)

class Srv(http.server.ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True
    def verify_request(self, request, client_address):
        try:
            request.settimeout(3.0)
            p = request.recv(1, socket.MSG_PEEK)
            if not p or p[0] in (0x16, 0x80):
                return False
            request.settimeout(30.0)
        except Exception:
            pass
        return True

srv = Srv(("0.0.0.0", PORT), H)
print("=" * 70)
print("  Kobo Arc 7 Freedom v2.5.2 — Wi-Fi OTA Rooting & Installer LIVE!")
print("  Open Browser on your Kobo Arc 7 and type:")
for ip in get_ips():
    print(f"\n      http://{ip}:{PORT}")
print("\n" + "=" * 70, flush=True)
srv.serve_forever()
PYEOF
