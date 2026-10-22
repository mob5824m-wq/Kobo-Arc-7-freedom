#!/usr/bin/env bash
# ==============================================================================
# Kobo Arc 7 Freedom v1.9.1 — 1-Click macOS / Linux Wi-Fi OTA Rooting Server (:8000)
#
# Concurrent HTTP/1.1 + `Range: bytes=...` (`206 Partial Content`) + instant
# TLS ClientHello reject so Kobo's stock 2013 Browser & Android DownloadManager
# stream every `.apk` cleanly with zero stalls or redirects!
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -f "${SCRIPT_DIR}/serve-over-wifi.py" ] && [ -f "${SCRIPT_DIR}/../releases/KoboArc7Freedom.apk" ]; then
    exec python3 "${SCRIPT_DIR}/serve-over-wifi.py" "$@"
fi

SERVE_DIR="${SCRIPT_DIR}/Kobo-WiFi-Files"
mkdir -p "${SERVE_DIR}"

RAW_BASE="https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases"
RELEASE_BASE="https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download"
for APK in Upgrade-My-Kobo-To-v2.2.0.apk KoboArc7Freedom.apk VLC.apk AuroraStore.apk VanillaAndroid4Home.apk SHA256SUMS.txt; do
    if [ -f "${SCRIPT_DIR}/${APK}" ] && [ ! -s "${SERVE_DIR}/${APK}" ]; then
        cp -f "${SCRIPT_DIR}/${APK}" "${SERVE_DIR}/${APK}"
    fi
    if [ ! -s "${SERVE_DIR}/${APK}" ]; then
        echo "==> Downloading ${APK}..."
        curl -fL --progress-bar "${RAW_BASE}/${APK}" -o "${SERVE_DIR}/${APK}" \
            || curl -fL --progress-bar "${RELEASE_BASE}/${APK}" -o "${SERVE_DIR}/${APK}"
    fi
done

if [ -f "${SCRIPT_DIR}/serve-over-wifi.py" ]; then
    ARC7_SERVE_DIR="${SERVE_DIR}" exec python3 "${SCRIPT_DIR}/serve-over-wifi.py" "$@"
fi

python3 - "${SERVE_DIR}" "${1:-8000}" << 'PYEOF'
import html, http.server, os, re, socket, subprocess, sys, urllib.parse

SERVE_DIR = sys.argv[1]
PORT = int(sys.argv[2])

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
        for f in sorted(os.listdir(SERVE_DIR)):
            fp = os.path.join(SERVE_DIR, f)
            if not os.path.isfile(fp):
                continue
            mb = os.path.getsize(fp) / 1048576.0
            cls = "btn primary" if f.startswith(("Upgrade-My-Kobo", "KoboArc7Freedom")) else "btn"
            items.append(f"<a class='{cls}' href='./{urllib.parse.quote(f)}'>Download {html.escape(f)} ({mb:.2f} MB)</a>")
            if f.lower().endswith(".apk"):
                items.append(f"<a class='sub' href='./raw/{urllib.parse.quote(f)}'>Alternate Direct Stream ({html.escape(f)})</a>")
        return (
            "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            "<title>Kobo Arc 7 v1.9.1 OTA Portal</title>"
            "<style>body{font-family:sans-serif;background:#0e1319;color:#f8fafc;padding:20px;}"
            ".card{background:#161e28;border:1px solid #2b394b;border-radius:10px;padding:20px;max-width:640px;margin:auto;}"
            "h1{color:#38bdf8;font-size:20px;margin-top:0;}.btn{display:block;background:#1f2a38;color:#fff;"
            "text-decoration:none;padding:14px 18px;border-radius:8px;margin:10px 0 4px 0;font-weight:bold;border:1px solid #475569;text-align:center;}"
            ".primary{background:#0284c7;border-color:#7dd3fc;font-size:17px;}"
            ".sub{display:block;color:#93c5fd;text-align:center;font-size:12.5px;margin-bottom:10px;}</style></head><body><div class='card'>"
            "<h1>Kobo Arc 7 Freedom v1.9.1 — Wi-Fi OTA Rooting &amp; Installer</h1>"
            "<p>1. Enable <b>Settings &rarr; Security &rarr; Unknown sources</b> on your tablet.<br>"
            "2. Tap <b>KoboArc7Freedom.apk</b> (new root) or <b>Upgrade-My-Kobo-To-v1.9.1.apk</b>.<br>"
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
        mime = "application/octet-stream" if force_octet else (
            "application/vnd.android.package-archive" if fname.lower().endswith(".apk") else "text/plain; charset=utf-8"
        )
        self.send_response(code)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
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
print("  Kobo Arc 7 Freedom v1.9.1 — Wi-Fi OTA Rooting & Installer LIVE!")
print("  Open Kobo's Browser on your tablet and type:")
for ip in get_ips():
    print(f"\n      http://{ip}:{PORT}")
print("\n" + "=" * 70, flush=True)
srv.serve_forever()
PYEOF
