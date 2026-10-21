#!/usr/bin/env python3
"""
Zero-Cable, Zero-Driver, Zero-SSL Local Home Wi-Fi Sideload Server for Kobo Arc 7!

Why this script exists:
  Before `KoboArc7Freedom.apk` is installed on your Kobo Arc 7, Kobo's stock 2013
  Browser cannot open modern `https://github.com/...` URLs (TLS 1.0 + expired CAs),
  and you may not want to hunt down a microSD card reader or USB ADB drivers.

  Plain `http://192.168.x.x:8000` over your home Wi-Fi DOES NOT USE SSL AT ALL —
  so Kobo Arc 7's stock 2013 Browser opens it 100% natively!

Usage on any PC / Mac / Linux laptop on the same Wi-Fi as your Kobo Arc 7:
  python3 tools/serve-over-wifi.py
"""
import html
import http.server
import os
import socket
import urllib.parse

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELEASES_DIR = os.path.join(ROOT_DIR, "releases")
PORT = 8000


def detect_lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


class Arc7Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=RELEASES_DIR, **kwargs)

    def guess_type(self, path: str) -> str:
        if path.lower().endswith(".apk"):
            return "application/vnd.android.package-archive"
        return super().guess_type(path)

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            files = sorted(f for f in os.listdir(RELEASES_DIR) if os.path.isfile(os.path.join(RELEASES_DIR, f)))
            items = []
            for f in files:
                sz_mb = os.path.getsize(os.path.join(RELEASES_DIR, f)) / (1024.0 * 1024.0)
                href = urllib.parse.quote(f)
                cls = "btn primary" if f == "KoboArc7Freedom.apk" else "btn"
                items.append(f"<a class='{cls}' href='/{href}'>Download {html.escape(f)} ({sz_mb:.2f} MB)</a>")

            body = (
                "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                "<title>Kobo Arc 7 Wi-Fi Installer</title>"
                "<style>"
                "body{font-family:sans-serif;background:#0e1319;color:#f8fafc;padding:24px;}"
                ".card{background:#161e28;border:1px solid #2b394b;border-radius:10px;padding:20px;max-width:640px;margin:auto;}"
                "h1{color:#38bdf8;font-size:22px;margin-top:0;}"
                ".btn{display:block;background:#1f2a38;color:#fff;text-decoration:none;padding:14px 18px;"
                "border-radius:8px;margin:10px 0;font-weight:bold;border:1px solid #475569;}"
                ".primary{background:#0284c7;border-color:#7dd3fc;font-size:17px;}"
                "</style></head><body><div class='card'>"
                "<h1>Kobo Arc 7 Freedom — Local Wi-Fi Installer</h1>"
                "<p>1. Make sure <b>Settings &rarr; Security &rarr; Unknown sources</b> is checked on your tablet.<br>"
                "2. Tap the blue button below to install <b>KoboArc7Freedom.apk</b> directly over Wi-Fi!<br>"
                "<i>(Tip: Drop any Android .zip ROM or .apk into the <code>releases/</code> folder on your computer and refresh this page to send it straight to your tablet!)</i></p>"
                + "".join(items)
                + "</div></body></html>"
            ).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        return super().do_GET()


if __name__ == "__main__":
    lan_ip = detect_lan_ip()
    print("=" * 68)
    print("  Kobo Arc 7 Freedom — Zero-SSL Home Wi-Fi Sideload Server Ready!")
    print(f"  Open Kobo's Browser on your tablet and type:")
    print(f"\n      http://{lan_ip}:{PORT}\n")
    print("  (Also drop any Android .zip ROMs into releases/ to serve them fast!)")
    print("=" * 68)
    http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Arc7Handler).serve_forever()
