#!/usr/bin/env bash
# ==============================================================================
# Kobo Arc 7 Freedom — 1-Click macOS / Linux Home Wi-Fi Sideload Server (:8000)
#
# Works both inside a cloned repository AND as a standalone single script downloaded
# straight into ~/Downloads (`bash Start-Kobo-WiFi-Server.sh`)!
# Automatically downloads KoboArc7Freedom.apk & VanillaAndroid4Home.apk from GitHub
# Releases if not already present locally, then starts the Zero-SSL HTTP/1.1 server.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -f "${SCRIPT_DIR}/serve-over-wifi.py" ] && [ -f "${SCRIPT_DIR}/../releases/KoboArc7Freedom.apk" ]; then
    exec python3 "${SCRIPT_DIR}/serve-over-wifi.py" "$@"
fi

SERVE_DIR="${SCRIPT_DIR}/Kobo-WiFi-Files"
mkdir -p "${SERVE_DIR}"

BASE_RELEASE_URL="https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download"
for APK in KoboArc7Freedom.apk VanillaAndroid4Home.apk SHA256SUMS.txt; do
    if [ ! -s "${SERVE_DIR}/${APK}" ]; then
        echo "==> Downloading ${APK} from GitHub Releases..."
        curl -fL --progress-bar "${BASE_RELEASE_URL}/${APK}" -o "${SERVE_DIR}/${APK}"
    fi
done

if [ -f "${SCRIPT_DIR}/serve-over-wifi.py" ]; then
    ARC7_SERVE_DIR="${SERVE_DIR}" exec python3 "${SCRIPT_DIR}/serve-over-wifi.py" "$@"
fi

python3 - "${SERVE_DIR}" "${1:-8000}" << 'PYEOF'
import html, http.server, os, socket, subprocess, sys, urllib.parse

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

class H(http.server.SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=SERVE_DIR, **kw)
    def guess_type(self, p):
        return "application/vnd.android.package-archive" if p.lower().endswith(".apk") else super().guess_type(p)
    def _index_bytes(self):
        items = []
        for f in sorted(os.listdir(SERVE_DIR)):
            fp = os.path.join(SERVE_DIR, f)
            if not os.path.isfile(fp):
                continue
            mb = os.path.getsize(fp) / 1048576.0
            cls = "btn primary" if f == "KoboArc7Freedom.apk" else "btn"
            items.append(f"<a class='{cls}' href='./{urllib.parse.quote(f)}'>Download {html.escape(f)} ({mb:.2f} MB)</a>")
        return (
            "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            "<title>Kobo Arc 7 Wi-Fi Installer</title>"
            "<style>body{font-family:sans-serif;background:#0e1319;color:#f8fafc;padding:22px;}"
            ".card{background:#161e28;border:1px solid #2b394b;border-radius:10px;padding:20px;max-width:640px;margin:auto;}"
            "h1{color:#38bdf8;font-size:21px;margin-top:0;}.btn{display:block;background:#1f2a38;color:#fff;"
            "text-decoration:none;padding:14px 18px;border-radius:8px;margin:10px 0;font-weight:bold;border:1px solid #475569;text-align:center;}"
            ".primary{background:#0284c7;border-color:#7dd3fc;font-size:17px;}</style></head><body><div class='card'>"
            "<h1>Kobo Arc 7 Freedom — Local Wi-Fi Installer</h1>"
            "<p>1. Enable <b>Settings &rarr; Security &rarr; Unknown sources</b> on your tablet.<br>"
            "2. Tap the blue button below to download <b>KoboArc7Freedom.apk</b> over Wi-Fi.<br>"
            "3. Swipe down the top-left notification bar on your Kobo and tap <b>KoboArc7Freedom.apk</b> to install!</p>"
            + "".join(items) + "</div></body></html>"
        ).encode("utf-8")
    def do_HEAD(self):
        p = urllib.parse.urlsplit(self.path).path
        if p in ("", "/", "/index.html"):
            b = self._index_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(b)))
            self.send_header("Connection", "close")
            self.end_headers()
            return
        super().do_HEAD()
    def do_GET(self):
        p = urllib.parse.urlsplit(self.path).path
        if p in ("", "/", "/index.html"):
            b = self._index_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(b)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(b)
            return
        super().do_GET()

class Srv(http.server.ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True

srv = Srv(("0.0.0.0", PORT), H)
print("=" * 68)
print("  Kobo Arc 7 Freedom — Home Wi-Fi Sideload Server LIVE!")
print("  Open Kobo's Browser on your tablet and type:")
for ip in get_ips():
    print(f"\n      http://{ip}:{PORT}")
print("\n" + "=" * 68, flush=True)
srv.serve_forever()
PYEOF
