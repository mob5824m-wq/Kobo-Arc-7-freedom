#!/usr/bin/env python3
"""
Zero-Cable, Zero-Driver, Zero-SSL Local Home Wi-Fi & Live Web Installer Server
for Kobo Arc 7 & Kobo Arc 7HD (`tools/serve-over-wifi.py`).

Fixes all common home-network, OS firewall, WSL2/container, and 2013 Android 4.2
Stock Browser (`DownloadManager`) failure modes:
  1. Multi-interface LAN IPv4 discovery (prioritizing real Wi-Fi/Ethernet adapters
     and warning clearly if run inside WSL2, VPN, or a cloud sandbox container).
  2. Full HTTP/1.1 + `HEAD` + `Range` (`206 Partial Content`) + `SO_REUSEADDR` +
     `Content-Disposition` attachment headers required by Android 4.2 DownloadManager.
  3. Instant `<1ms` fast-reject of TLS `ClientHello` (`0x16`/`0x80`) so modern Chrome/
     Edge/Safari browsers with HTTPS-First Mode immediately fall back to `http://`.
  4. Dual MIME download buttons (`application/vnd.android.package-archive` AND
     `/raw/` `application/octet-stream` fallback) + live ES3 transfer progress bar
     compatible with 2013 WebKit 534.30 AND 1-click Windows (`.bat`) / Mac+Linux (`.sh`)
     standalone home Wi-Fi server launchers!

Usage:
  python3 tools/serve-over-wifi.py              # Start server on 0.0.0.0:8000
  python3 tools/serve-over-wifi.py 8080         # Custom port
  python3 tools/serve-over-wifi.py --write-static  # Generate static docs/index.html & releases/index.html
"""
import html
import http.server
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.parse

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELEASES_DIR = os.environ.get("ARC7_SERVE_DIR") or os.path.join(ROOT_DIR, "releases")
DOCS_DIR = os.path.join(ROOT_DIR, "docs")
TOOLS_DIR = os.path.join(ROOT_DIR, "tools")
DEFAULT_PORT = 8000
GITHUB_REPO = "mob5824m-wq/Kobo-Arc-7-freedom"
GITHUB_LATEST_BASE = f"https://github.com/{GITHUB_REPO}/releases/latest/download"

_STATE_LOCK = threading.Lock()
_TRANSFERS = {
    "active_file": "",
    "bytes_sent": 0,
    "bytes_total": 0,
    "completed_count": 0,
    "last_completed_file": "",
    "last_client_ip": "",
    "updated_at": 0.0,
}


def detect_environment_and_lan_ips():
    """
    Returns `(lan_ips, env_kind)` where `env_kind` is one of:
      - `'lan'`   : Direct home/office Wi-Fi or Ethernet machine
      - `'wsl'`   : Windows Subsystem for Linux (NAT behind Windows host)
      - `'cloud'` : Cloud container / link-local sandbox (e.g. 169.254.x.x)
    """
    is_wsl = False
    try:
        with open("/proc/version", "r", encoding="utf-8", errors="ignore") as f:
            ver = f.read().lower()
            if "microsoft" in ver or "wsl" in ver:
                is_wsl = True
    except OSError:
        pass

    preferred = []
    secondary = []
    saw_link_local = False

    def _record(ip_str: str, iface: str = "") -> None:
        nonlocal saw_link_local
        ip_str = ip_str.strip()
        if not re.match(r"^\d+\.\d+\.\d+\.\d+$", ip_str):
            return
        if ip_str.startswith("127.") or ip_str == "0.0.0.0":
            return
        if ip_str.startswith("169.254."):
            saw_link_local = True
            return
        low = iface.lower()
        if any(tok in low for tok in ("docker", "br-", "veth", "virbr", "tun", "tap", "wg", "tailscale", "utun")):
            if ip_str not in secondary and ip_str not in preferred:
                secondary.append(ip_str)
            return
        is_wifi_eth = any(low.startswith(p) for p in ("wl", "wlan", "en", "eth", "wi-fi", "wifi"))
        target = preferred if is_wifi_eth else secondary
        if ip_str not in preferred and ip_str not in secondary:
            target.append(ip_str)

    # Linux `ip -4 -o addr show up`
    try:
        out = subprocess.check_output(
            ["ip", "-4", "-o", "addr", "show", "up"],
            text=True, stderr=subprocess.DEVNULL, timeout=2,
        )
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 4:
                iface = parts[1]
                ip_part = parts[3].split("/")[0]
                _record(ip_part, iface)
    except Exception:
        pass

    # macOS / BSD `ifconfig`
    if not preferred and platform.system() == "Darwin":
        try:
            out = subprocess.check_output(["ifconfig"], text=True, stderr=subprocess.DEVNULL, timeout=2)
            cur_if = ""
            for line in out.splitlines():
                if line and not line[0].isspace() and ":" in line:
                    cur_if = line.split(":", 1)[0]
                elif "inet " in line:
                    m = re.search(r"inet\s+(\d+\.\d+\.\d+\.\d+)", line)
                    if m:
                        _record(m.group(1), cur_if)
        except Exception:
            pass

    # UDP route probe fallback (works across Windows, macOS, Linux)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        _record(s.getsockname()[0], "default")
    except Exception:
        pass
    finally:
        s.close()

    # Windows / hostname multi-adapter enumeration
    try:
        _, _, host_ips = socket.gethostbyname_ex(socket.gethostname())
        for hip in host_ips:
            _record(hip, "host")
    except Exception:
        pass

    all_ips = preferred + [x for x in secondary if x not in preferred]
    if is_wsl:
        return (all_ips or ["127.0.0.1"], "wsl")
    if not all_ips and saw_link_local:
        return ([], "cloud")
    return (all_ips, "lan" if all_ips else "cloud")


def render_portal_html(static_mode: bool = False, port: int = DEFAULT_PORT) -> str:
    lan_ips, env_kind = ([], "static") if static_mode else detect_environment_and_lan_ips()

    files_meta = []
    if os.path.isdir(RELEASES_DIR):
        for fname in sorted(os.listdir(RELEASES_DIR)):
            if fname in ("index.html",) or fname.startswith("."):
                continue
            fpath = os.path.join(RELEASES_DIR, fname)
            if os.path.isfile(fpath):
                files_meta.append((fname, os.path.getsize(fpath)))

    def _href(fname: str) -> str:
        q = urllib.parse.quote(fname)
        if static_mode:
            # Direct zero-302-redirect Fastly CDN raw binary stream from `main` branch!
            # Works on GitHub Pages (`mob5824m-wq.github.io`), `htmlpreview.github.io`, `raw.githack.com`, AND local HTML!
            return f"https://raw.githubusercontent.com/{GITHUB_REPO}/main/releases/{q}"
        return f"./{q}"

    def _mirror_href(fname: str) -> str:
        return f"{GITHUB_LATEST_BASE}/{urllib.parse.quote(fname)}"

    # Build primary download cards (`Upgrade-My-Kobo-To-v1.9.0.apk` FIRST so existing users never hit a key mismatch!)
    upgrader_html = ""
    main_html = ""
    aurora_html = ""
    apk_buttons = []
    other_buttons = []
    for fname, sz in files_meta:
        mb = sz / 1048576.0
        size_txt = f"{mb:.2f} MB" if mb >= 0.1 else f"{max(1, sz // 1024)} KB"
        link = _href(fname)
        mirror = _mirror_href(fname)
        if fname == "Upgrade-My-Kobo-To-v1.9.0.apk":
            upgrader_html = (
                f"<div class='dl-group' style='border:1px solid #10b981;padding:12px;border-radius:12px;background:#071915;margin-bottom:12px;'>"
                f"<div style='color:#6ee7b7;font-size:13px;font-weight:bold;margin-bottom:8px;'>"
                f"&#9889; 1-TAP OVER-THE-AIR UPGRADE &amp; ROOT INSTALLER (v1.9.0 — Auto Timezone + Aurora Store + Python 3 + apt + Long-Press Widgets + Chimes):</div>"
                f"<a class='btn primary' href='{link}' onclick='onStartDl(\"Upgrade-My-Kobo-To-v1.9.0.apk\")'>"
                f"&#11015; 1-Tap OTA Root &amp; Upgrade — Upgrade-My-Kobo-To-v1.9.0.apk ({size_txt})</a>"
                + (
                    f"<a class='btn subtle' href='./raw/{urllib.parse.quote(fname)}' onclick='onStartDl(\"Upgrade-My-Kobo-To-v1.9.0.apk\")'>"
                    f"&#128295; Alternate Direct Binary Stream for Upgrader (tap if Kobo Browser says &ldquo;Cannot download&rdquo;)</a>"
                    if not static_mode
                    else f"<a class='btn subtle' href='{mirror}'>&#9729; GitHub Releases Mirror — Upgrade-My-Kobo-To-v1.9.0.apk</a>"
                )
                + "</div>"
            )
        elif fname == "KoboArc7Freedom.apk":
            main_html = (
                f"<div class='dl-group'>"
                f"<a class='btn primary' href='{link}' onclick='onStartDl(\"KoboArc7Freedom.apk\")'>"
                f"&#11015; Install / Direct Update: KoboArc7Freedom.apk v1.9.0 ({size_txt}) — Full Root + Suite + Auto Timezone + Python 3 + apt</a>"
                + (
                    f"<a class='btn subtle' href='./raw/{urllib.parse.quote(fname)}' onclick='onStartDl(\"KoboArc7Freedom.apk\")'>"
                    f"&#128295; Alternate Direct Binary Stream (tap this if Kobo Browser says &ldquo;Cannot download&rdquo;)</a>"
                    if not static_mode
                    else f"<a class='btn subtle' href='{mirror}'>&#9729; GitHub Releases Mirror — KoboArc7Freedom.apk</a>"
                )
                + "</div>"
            )
        elif fname == "AuroraStore.apk":
            aurora_html = (
                f"<a class='btn secondary' href='{link}' onclick='onStartDl(\"AuroraStore.apk\")'>"
                f"&#11015; Download Standalone Aurora Store (Android 4.2 + TLS 1.3 Play Store Client) — AuroraStore.apk ({size_txt})</a>"
            )
        elif fname == "VanillaAndroid4Home.apk":
            apk_buttons.append(
                f"<a class='btn secondary' href='{link}' onclick='onStartDl(\"VanillaAndroid4Home.apk\")'>"
                f"&#11015; Download Clean Android 4.2 Home Launcher v49 (Long-Press Widgets) — VanillaAndroid4Home.apk ({size_txt})</a>"
            )
        elif fname.lower().endswith((".bat", ".sh")):
            continue
        else:
            other_buttons.append(
                f"<a class='btn small' href='{link}'>&#128196; {html.escape(fname)} ({size_txt})</a>"
            )
    if aurora_html:
        apk_buttons.insert(0, aurora_html)
    if main_html:
        apk_buttons.insert(0, main_html)
    if upgrader_html:
        apk_buttons.insert(0, upgrader_html)

    bat_href = "./Start-Kobo-WiFi-Server.bat"
    sh_href = "./Start-Kobo-WiFi-Server.sh"
    adb_bat_href = "./Backup-Kobo-To-Laptop-ADB.bat"
    adb_sh_href = "./Backup-Kobo-To-Laptop-ADB.sh"
    logo_src = "./logo-banner.png" if os.path.isfile(os.path.join(DOCS_DIR, "logo-banner.png")) else ""

    # Context banner tailored accurately to where this page is being viewed
    if env_kind == "lan" and lan_ips:
        urls_html = "".join(
            f"<div class='url-pill'>http://{html.escape(ip)}:{port}</div>" for ip in lan_ips
        )
        top_banner = (
            "<div class='banner ok'>"
            "<b>&#9989; Home Wi-Fi Sideload Server ACTIVE on Your Network!</b><br>"
            "Open Kobo's built-in 2013 Browser on your Kobo Arc 7 and type this exact address "
            "(include <code>http://</code>, NOT <code>https://</code>):"
            f"{urls_html}"
            "</div>"
        )
    elif env_kind == "wsl":
        top_banner = (
            "<div class='banner warn'>"
            "<b>&#9888; Running Inside Windows WSL2 (NAT Subnet Detected):</b><br>"
            "WSL2 uses a private virtual IP unreachable from Wi-Fi tablets by default. "
            "Click <b>1-Click Windows Home Wi-Fi Server (.bat)</b> below and double-click it directly in Windows — "
            "it opens Windows Firewall automatically and needs <b>zero Python</b>!"
            "</div>"
        )
    else:
        # Cloud live preview OR GitHub Pages static portal!
        top_banner = (
            "<div class='banner info'>"
            "<b>&#128640; Choose Your Fastest Zero-Hassle Way to Install onto Kobo Arc 7:</b><br>"
            "<i>Why Kobo's stock 2013 Browser needs plain <code>http://</code> once:</i> Factory Kobo OS lacks modern 2026 "
            "TLS 1.3 certificates until <code>KoboArc7Freedom.apk</code> is installed. Pick <b>Method A (Wi-Fi)</b> or "
            "<b>Method B (MicroSD)</b> below — once installed, your tablet gets all <b>120 modern 2026 Root CAs + TLS 1.3 Browser</b> permanently!"
            "</div>"
        )

    live_progress_widget = (
        ""
        if static_mode
        else (
            "<div id='xferBox' class='xfer' style='display:none;'>"
            "<div id='xferTitle' class='xfer-title'>Preparing transfer to Kobo Arc 7...</div>"
            "<div class='bar-bg'><div id='xferBar' class='bar-fill' style='width:4%;'></div></div>"
            "<div id='xferHint' class='xfer-hint'>"
            "Once 100% complete: swipe down from the <b>top-left corner</b> of your Kobo screen and tap "
            "<b>KoboArc7Freedom.apk</b> &rarr; <b>Install</b>!"
            "</div></div>"
        )
    )

    upload_section = (
        ""
        if static_mode
        else (
            "<div class='card'>"
            "<h2>&#128228; Drop Any Extra ROM (.zip), E-Book (.epub/.cbz) or App (.apk) into Wi-Fi Server</h2>"
            "<p>Upload any file from your computer right here — refresh this page on your Kobo Arc 7 to download it over Wi-Fi!</p>"
            "<form method='POST' action='./upload' enctype='multipart/form-data'>"
            "<input type='file' name='file' required style='margin:6px 10px 6px 0;color:#e2e8f0;'>"
            "<button type='submit' class='btn-inline'>Upload to Wi-Fi Server</button>"
            "</form></div>"
        )
    )

    js_script = (
        ""
        if static_mode
        else (
            "<script type='text/javascript'>"
            "var pollTimer=null;"
            "function updateStatus(){"
            "  try{"
            "    var x=new XMLHttpRequest();"
            "    x.open('GET','./api/status?t='+(new Date().getTime()),true);"
            "    x.onreadystatechange=function(){"
            "      if(x.readyState===4&&x.status===200){"
            "        var d=eval('('+x.responseText+')');"
            "        var box=document.getElementById('xferBox');"
            "        var ttl=document.getElementById('xferTitle');"
            "        var bar=document.getElementById('xferBar');"
            "        if(!box)return;"
            "        if(d.bytes_total>0&&d.bytes_sent>0&&d.bytes_sent<d.bytes_total){"
            "          var pct=Math.round((d.bytes_sent*100)/d.bytes_total);"
            "          box.style.display='block';"
            "          bar.style.width=pct+'%';"
            "          ttl.innerHTML='Streaming <b>'+d.active_file+'</b> over Wi-Fi: <b>'+pct+'%</b> ('+"
            "            (d.bytes_sent/1048576).toFixed(1)+' / '+(d.bytes_total/1048576).toFixed(1)+' MB)';"
            "        }else if(d.completed_count>0){"
            "          box.style.display='block';"
            "          bar.style.width='100%';"
            "          ttl.innerHTML='&#9989; <b>100% Transfer Delivered ('+d.last_completed_file+')!</b>';"
            "        }"
            "      }"
            "    };"
            "    x.send(null);"
            "  }catch(e){}"
            "}"
            "function onStartDl(name){"
            "  var box=document.getElementById('xferBox');"
            "  var ttl=document.getElementById('xferTitle');"
            "  if(box){box.style.display='block';ttl.innerHTML='Starting download of <b>'+name+'</b> — check top-left Notification Bar on Kobo...';}"
            "  if(!pollTimer){pollTimer=setInterval(updateStatus,900);}"
            "}"
            "pollTimer=setInterval(updateStatus,2000);"
            "</script>"
        )
    )

    logo_html = (
        f"<div style='text-align:center;margin-bottom:14px;'>"
        f"<img src='{logo_src}' alt='Kobo Arc 7 Freedom' style='max-width:100%;max-height:118px;border-radius:10px;'>"
        f"</div>"
        if logo_src
        else ""
    )

    return (
        "<!DOCTYPE html><html><head><meta charset='utf-8'>"
        "<meta name='viewport' content='width=device-width,initial-scale=1'>"
        "<title>Kobo Arc 7 Freedom — Installer &amp; Wi-Fi Sideload Portal</title>"
        "<style>"
        "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;"
        "background:#0b1017;color:#f1f5f9;margin:0;padding:18px 14px;}"
        ".wrap{max-width:780px;margin:0 auto;}"
        ".card{background:#151e29;border:1px solid #2b3b4e;border-radius:12px;padding:20px;margin-bottom:16px;}"
        "h1{color:#38bdf8;font-size:23px;margin:0 0 6px 0;}"
        "h2{color:#7dd3fc;font-size:17px;margin:0 0 10px 0;}"
        "p,li{color:#cbd5e1;font-size:14.5px;line-height:1.55;margin:6px 0;}"
        "code{background:#0b1320;color:#7dd3fc;padding:2px 6px;border-radius:4px;font-size:13.5px;border:1px solid #1e3a5f;}"
        ".banner{border-radius:10px;padding:15px 18px;margin-bottom:16px;font-size:14.5px;line-height:1.5;}"
        ".banner.ok{background:#07291e;border:1px solid #10b981;color:#d1fae5;}"
        ".banner.warn{background:#2c1d09;border:1px solid #f59e0b;color:#fef3c7;}"
        ".banner.info{background:#0d2137;border:1px solid #0284c7;color:#e0f2fe;}"
        ".url-pill{background:#07131f;border:2px solid #38bdf8;color:#fff;font-family:monospace;"
        "font-size:21px;font-weight:bold;padding:10px 14px;border-radius:8px;margin-top:10px;text-align:center;}"
        ".btn{display:block;text-decoration:none;padding:15px 18px;border-radius:9px;margin:10px 0;"
        "font-weight:bold;text-align:center;font-size:16px;color:#ffffff;border:1px solid #475569;background:#1f2a38;}"
        ".btn.primary{background:#0284c7;border-color:#7dd3fc;font-size:17.5px;}"
        ".btn.primary:hover{background:#0369a1;}"
        ".btn.win{background:#15803d;border-color:#86efac;}"
        ".btn.mac{background:#1e3a8a;border-color:#93c5fd;}"
        ".btn.secondary{background:#1e293b;border-color:#64748b;font-size:14.5px;}"
        ".btn.subtle{background:#0f172a;border-color:#334155;color:#93c5fd;font-size:13px;padding:10px 14px;}"
        ".btn.small{display:inline-block;padding:8px 13px;font-size:13px;margin:4px 6px 4px 0;}"
        ".btn-inline{background:#0284c7;color:#fff;border:1px solid #7dd3fc;padding:9px 16px;border-radius:7px;font-weight:bold;cursor:pointer;}"
        ".xfer{background:#07291e;border:1px solid #10b981;border-radius:9px;padding:14px;margin:12px 0;}"
        ".xfer-title{color:#a7f3d0;font-size:14.5px;margin-bottom:8px;}"
        ".xfer-hint{color:#cbd5e1;font-size:13px;margin-top:8px;}"
        ".bar-bg{background:#0f172a;height:12px;border-radius:6px;overflow:hidden;border:1px solid #1e293b;}"
        ".bar-fill{background:#10b981;height:100%;}"
        ".grid2{width:100%;}"
        "</style></head><body><div class='wrap'>"
        + logo_html
        + "<div class='card'>"
        "<h1>Kobo Arc 7 Freedom v1.9.0 — Over-The-Air Rooting Portal, Auto Timezone, Aurora Store, Python 3 + apt &amp; Animated Suite</h1>"
        "<p>Live Direct OTA APK Downloads (Zero Redirects!) &bull; Automatic Local Timezone + Atomic Clock &bull; "
        "Bundled Aurora Store + F-Droid &bull; Built-In Python 3 &amp; apt &bull; Long-Press Home Widgets &bull; 12h + Apple/Android Chimes &amp; Per-Alarm Volume</p>"
        + live_progress_widget
        + "".join(apk_buttons)
        + ("".join(other_buttons) if other_buttons else "")
        + "</div>"
        + top_banner
        + "<div class='card'>"
        "<h2>&#128187; NEW in v1.8.0 — 1-Click Direct Laptop Backup &amp; Restore Over USB ADB (0 MB Tablet Space!)</h2>"
        "<p>Stream bit-for-bit flash partitions (<code>boot</code>, <code>recovery</code>, <code>nvram</code> Wi-Fi MAC calibration, "
        "<code>uboot</code>, <code>system</code> OS) + your Books, Music &amp; Photos straight over USB onto your laptop — with cryptographic SHA-256 verification:</p>"
        f"<a class='btn win' href='{adb_bat_href}'>&#128187; Download 1-Click Windows USB ADB Backup Tool (Backup-Kobo-To-Laptop-ADB.bat — Auto-Installs ADB!)</a>"
        f"<a class='btn mac' href='{adb_sh_href}'>&#128187; Download 1-Click macOS / Linux USB ADB Backup Tool (Backup-Kobo-To-Laptop-ADB.sh)</a>"
        "<p><i>Or with any laptop web browser:</i> plug USB in, run <code>adb forward tcp:18777 tcp:18777</code>, and open <code>http://127.0.0.1:18777</code>!</p>"
        "</div>"
        + "<div class='card'>"
        "<h2>&#128246; Method A — 1-Click Home Wi-Fi Installer (No USB Cable, No MicroSD, No Python Needed!)</h2>"
        "<p>Download &amp; run the 1-click launcher on any computer connected to the <b>same home Wi-Fi</b> as your Kobo Arc 7. "
        "It automatically opens your firewall, displays your exact <code>http://192.168.x.x:8000</code> address, "
        "and serves <b>KoboArc7Freedom.apk</b> over plain zero-SSL HTTP straight to Kobo's stock 2013 Browser:</p>"
        f"<a class='btn win' href='{bat_href}'>&#129695; Download 1-Click Windows Home Wi-Fi Server (Start-Kobo-WiFi-Server.bat — Zero Python Needed!)</a>"
        f"<a class='btn mac' href='{sh_href}'>&#128039; Download 1-Click macOS / Linux Home Wi-Fi Server (Start-Kobo-WiFi-Server.sh)</a>"
        "<ol>"
        "<li>On your Kobo Arc 7, enable <b>Settings &rarr; Security &rarr; Unknown sources</b>.</li>"
        "<li>Double-click <b>Start-Kobo-WiFi-Server.bat</b> on Windows (or run <code>bash Start-Kobo-WiFi-Server.sh</code> on Mac/Linux).</li>"
        "<li>Open Kobo's Browser and enter the shown <code>http://192.168.x.x:8000</code> address (be sure to start with <code>http://</code>!).</li>"
        "<li>Tap <b>Download KoboArc7Freedom.apk</b>, then swipe down the <b>top-left notification shade</b> and tap the downloaded file to install!</li>"
        "</ol>"
        "</div>"
        "<div class='card'>"
        "<h2>&#128190; Method B — MicroSD Card Trick (Works Completely Offline!)</h2>"
        "<ol>"
        "<li>Click <b>Download KoboArc7Freedom.apk</b> at the top of this page and copy it to the root of any FAT32 microSD card.</li>"
        "<li>Insert the microSD card into your Kobo Arc 7, open Kobo's Browser, and type:<br>"
        "<code>file:///storage/sdcard1/KoboArc7Freedom.apk</code></li>"
        "<li>Tap <b>Install</b> — you're in!</li>"
        "</ol>"
        "</div>"
        "<div class='card'>"
        "<h2>&#128193; Using On-Tablet Wi-Fi File Drive (<code>http://&lt;tablet-ip&gt;:8080</code>)</h2>"
        "<p>Once <b>KoboArc7Freedom.apk</b> is installed on your tablet, open <b>Tab 5 (TOOLS) &rarr; Wi-Fi Drive</b>:</p>"
        "<ul>"
        "<li>Tapping <b>Wi-Fi Drive</b> now starts the foreground server <b>immediately</b> and gives you a direct "
        "1-step URL (<code>http://192.168.x.x:8080/?pin=XXXX</code>) plus a <b>Disable PIN</b> toggle!</li>"
        "<li>Always include <code>http://</code> in Chrome/Safari/Edge so HTTPS-First mode connects instantaneously.</li>"
        "</ul>"
        "</div>"
        + upload_section
        + js_script
        + "</div></body></html>"
    )


class Arc7HTTPServer(http.server.ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True

    def verify_request(self, request, client_address):
        """
        Peek first byte of incoming TCP stream: if a modern browser's HTTPS-First
        feature sends a TLS `ClientHello` (`0x16`) or SSLv2 hello (`0x80`) to our
        plain HTTP port, immediately close in <1ms without error noise so the
        browser falls back to `http://` instantaneously!
        """
        try:
            request.settimeout(3.0)
            peek = request.recv(1, socket.MSG_PEEK)
            if not peek or peek[0] in (0x16, 0x80):
                return False
            request.settimeout(30.0)
        except socket.timeout:
            return False
        except OSError:
            return False
        return True


class Arc7Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_port_num = DEFAULT_PORT

    def log_message(self, fmt, *args):
        sys.stdout.write(f"[Wi-Fi Server] {self.address_string()} - {fmt % args}\n")
        sys.stdout.flush()

    def _send_common_headers(self, content_type: str, content_length: int, extra=None) -> None:
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(content_length))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store, no-transform")
        self.send_header("Connection", "close")
        if extra:
            for k, v in extra.items():
                self.send_header(k, v)
        self.end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self._send_common_headers("text/plain", 0, {"Access-Control-Allow-Methods": "GET, HEAD, POST, OPTIONS"})

    def do_HEAD(self):
        self._serve(head_only=True)

    def do_GET(self):
        self._serve(head_only=False)

    def do_POST(self):
        parsed = urllib.parse.urlsplit(self.path)
        if parsed.path != "/upload":
            self.send_response(404)
            self._send_common_headers("text/plain; charset=utf-8", 9)
            self.wfile.write(b"Not Found")
            return
        try:
            ctype = self.headers.get("Content-Type", "")
            clen = int(self.headers.get("Content-Length", "0"))
            m = re.search(r"boundary=([^;\s]+)", ctype)
            if not m or clen <= 0:
                raise ValueError("Invalid multipart upload")
            boundary = ("--" + m.group(1).strip('"')).encode("latin-1")
            raw = self.rfile.read(clen)
            parts = raw.split(boundary)
            saved_name = ""
            os.makedirs(RELEASES_DIR, exist_ok=True)
            for part in parts:
                if b"\r\n\r\n" not in part:
                    continue
                hdr_bytes, body = part.split(b"\r\n\r\n", 1)
                hdr_str = hdr_bytes.decode("utf-8", errors="ignore")
                fn_m = re.search(r'filename="([^"]+)"', hdr_str)
                if not fn_m:
                    continue
                clean = os.path.basename(fn_m.group(1)).strip()
                clean = re.sub(r"[^A-Za-z0-9._() -]", "_", clean) or "uploaded.apk"
                if body.endswith(b"\r\n--"):
                    body = body[:-4]
                elif body.endswith(b"\r\n"):
                    body = body[:-2]
                out_path = os.path.join(RELEASES_DIR, clean)
                with open(out_path, "wb") as wf:
                    wf.write(body)
                saved_name = clean
                print(f"[Wi-Fi Server] Saved uploaded file -> {out_path} ({len(body)} bytes)", flush=True)
                break
            self.send_response(303)
            self._send_common_headers("text/plain", 0, {"Location": f"./?uploaded={urllib.parse.quote(saved_name)}"})
        except Exception as exc:
            msg = f"Upload failed: {exc}".encode("utf-8")
            self.send_response(400)
            self._send_common_headers("text/plain; charset=utf-8", len(msg))
            self.wfile.write(msg)

    def _serve(self, head_only: bool) -> None:
        try:
            parsed = urllib.parse.urlsplit(self.path)
            req_path = parsed.path

            if req_path == "/favicon.ico":
                self.send_response(204)
                self._send_common_headers("image/x-icon", 0)
                return

            if req_path == "/api/status":
                lan_ips, env_kind = detect_environment_and_lan_ips()
                with _STATE_LOCK:
                    payload = dict(_TRANSFERS)
                payload["lan_ips"] = lan_ips
                payload["env_kind"] = env_kind
                payload["port"] = self.server_port_num
                body = json.dumps(payload).encode("utf-8")
                self.send_response(200)
                self._send_common_headers("application/json; charset=utf-8", len(body))
                if not head_only:
                    self.wfile.write(body)
                return

            if req_path in ("", "/", "/index.html"):
                body = render_portal_html(static_mode=False, port=self.server_port_num).encode("utf-8")
                self.send_response(200)
                self._send_common_headers("text/html; charset=utf-8", len(body))
                if not head_only:
                    self.wfile.write(body)
                return

            if req_path == "/logo-banner.png":
                banner_file = os.path.join(DOCS_DIR, "logo-banner.png")
                if os.path.isfile(banner_file):
                    self._stream_file(banner_file, "image/png", head_only, inline=True)
                    return

            if req_path == "/Start-Kobo-WiFi-Server.bat":
                bat_path = os.path.join(TOOLS_DIR, "serve-over-wifi.bat")
                if os.path.isfile(bat_path):
                    self._stream_file(bat_path, "application/octet-stream", head_only, download_name="Start-Kobo-WiFi-Server.bat")
                    return

            if req_path == "/Start-Kobo-WiFi-Server.sh":
                sh_path = os.path.join(TOOLS_DIR, "serve-over-wifi.sh")
                if os.path.isfile(sh_path):
                    self._stream_file(sh_path, "application/x-sh", head_only, download_name="Start-Kobo-WiFi-Server.sh")
                    return

            force_raw = False
            rel = req_path
            if rel.startswith("/raw/"):
                force_raw = True
                rel = "/" + rel[5:]

            decoded = os.path.basename(urllib.parse.unquote(rel.lstrip("/")))
            target = os.path.join(RELEASES_DIR, decoded)
            if decoded and os.path.isfile(target):
                if force_raw:
                    mime = "application/octet-stream"
                elif decoded.lower().endswith(".apk"):
                    mime = "application/vnd.android.package-archive"
                elif decoded.lower().endswith(".txt"):
                    mime = "text/plain; charset=utf-8"
                else:
                    mime = "application/octet-stream"
                self._stream_file(target, mime, head_only, download_name=decoded, inline=decoded.lower().endswith(".txt"))
                return

            msg = b"File not found. Return to / for the Kobo Arc 7 Installer Portal."
            self.send_response(404)
            self._send_common_headers("text/plain; charset=utf-8", len(msg))
            if not head_only:
                self.wfile.write(msg)
        except (BrokenPipeError, ConnectionResetError):
            # Normal when Android 4.2 WebKit probes headers then hands download off to DownloadManager
            pass

    def _stream_file(self, file_path: str, mime: str, head_only: bool,
                     download_name: str = "", inline: bool = False) -> None:
        total_size = os.path.getsize(file_path)
        start = 0
        end = total_size - 1
        status = 200

        range_hdr = self.headers.get("Range", "")
        if range_hdr.startswith("bytes=") and total_size > 0:
            m = re.match(r"bytes=(\d*)-(\d*)", range_hdr)
            if m:
                s_str, e_str = m.group(1), m.group(2)
                if s_str:
                    start = min(int(s_str), total_size - 1)
                if e_str:
                    end = min(int(e_str), total_size - 1)
                if start <= end:
                    status = 206

        length = (end - start + 1) if total_size > 0 else 0
        extra = {"Accept-Ranges": "bytes"}
        if status == 206:
            extra["Content-Range"] = f"bytes {start}-{end}/{total_size}"
        if download_name and not inline:
            extra["Content-Disposition"] = f'attachment; filename="{download_name}"'

        self.send_response(status)
        self._send_common_headers(mime, length, extra)
        if head_only or length <= 0:
            return

        track = download_name.lower().endswith(".apk")
        if track:
            with _STATE_LOCK:
                _TRANSFERS["active_file"] = download_name
                _TRANSFERS["bytes_sent"] = start
                _TRANSFERS["bytes_total"] = total_size
                _TRANSFERS["last_client_ip"] = self.client_address[0]
                _TRANSFERS["updated_at"] = time.time()

        sent_now = 0
        with open(file_path, "rb") as fp:
            fp.seek(start)
            remaining = length
            while remaining > 0:
                chunk = fp.read(min(65536, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                sent_now += len(chunk)
                remaining -= len(chunk)
                if track:
                    with _STATE_LOCK:
                        _TRANSFERS["bytes_sent"] = start + sent_now
                        _TRANSFERS["updated_at"] = time.time()

        if track and (start + sent_now) >= total_size:
            with _STATE_LOCK:
                _TRANSFERS["completed_count"] += 1
                _TRANSFERS["last_completed_file"] = download_name
                _TRANSFERS["updated_at"] = time.time()
            print(f"[Wi-Fi Server] 100% DELIVERED {download_name} ({total_size / 1048576:.2f} MB) -> {self.client_address[0]}!", flush=True)


def write_static_pages() -> None:
    os.makedirs(DOCS_DIR, exist_ok=True)
    os.makedirs(RELEASES_DIR, exist_ok=True)

    # Copy helper launchers into `docs/` & `releases/` so static hosting and GitHub Pages serve them directly too!
    shutil.copyfile(os.path.join(TOOLS_DIR, "serve-over-wifi.bat"), os.path.join(DOCS_DIR, "Start-Kobo-WiFi-Server.bat"))
    shutil.copyfile(os.path.join(TOOLS_DIR, "serve-over-wifi.sh"), os.path.join(DOCS_DIR, "Start-Kobo-WiFi-Server.sh"))
    os.chmod(os.path.join(DOCS_DIR, "Start-Kobo-WiFi-Server.sh"), 0o755)

    for adb_helper in ("Backup-Kobo-To-Laptop-ADB.bat", "Backup-Kobo-To-Laptop-ADB.sh"):
        src_h = os.path.join(RELEASES_DIR, adb_helper)
        if os.path.isfile(src_h):
            shutil.copyfile(src_h, os.path.join(DOCS_DIR, adb_helper))
            if adb_helper.endswith(".sh"):
                os.chmod(os.path.join(DOCS_DIR, adb_helper), 0o755)

    shutil.copyfile(os.path.join(TOOLS_DIR, "serve-over-wifi.bat"), os.path.join(RELEASES_DIR, "Start-Kobo-WiFi-Server.bat"))
    shutil.copyfile(os.path.join(TOOLS_DIR, "serve-over-wifi.sh"), os.path.join(RELEASES_DIR, "Start-Kobo-WiFi-Server.sh"))
    os.chmod(os.path.join(RELEASES_DIR, "Start-Kobo-WiFi-Server.sh"), 0o755)
    if os.path.isfile(os.path.join(DOCS_DIR, "logo-banner.png")):
        shutil.copyfile(os.path.join(DOCS_DIR, "logo-banner.png"), os.path.join(RELEASES_DIR, "logo-banner.png"))

    docs_html = render_portal_html(static_mode=True)
    with open(os.path.join(DOCS_DIR, "index.html"), "w", encoding="utf-8") as f:
        f.write(docs_html)
    with open(os.path.join(DOCS_DIR, ".nojekyll"), "w", encoding="utf-8") as f:
        f.write("")
    with open(os.path.join(RELEASES_DIR, "index.html"), "w", encoding="utf-8") as f:
        f.write(docs_html)
    print("Generated static web portals: docs/index.html + releases/index.html")


def main() -> None:
    if "--write-static" in sys.argv:
        write_static_pages()
        return

    os.chmod(os.path.join(TOOLS_DIR, "serve-over-wifi.sh"), 0o755)
    requested_port = DEFAULT_PORT
    for arg in sys.argv[1:]:
        if arg.isdigit():
            requested_port = int(arg)

    candidates = [requested_port] + [p for p in (8000, 8080, 8888) if p != requested_port]
    srv = None
    bound_port = requested_port
    for p in candidates:
        try:
            Arc7Handler.server_port_num = p
            srv = Arc7HTTPServer(("0.0.0.0", p), Arc7Handler)
            bound_port = p
            break
        except OSError:
            continue
    if srv is None:
        raise SystemExit("ERROR: Could not bind any HTTP port (tried 8000, 8080, 8888).")

    lan_ips, env_kind = detect_environment_and_lan_ips()
    print("=" * 70)
    print("  Kobo Arc 7 Freedom — Zero-SSL Wi-Fi & Web Installer Server READY!")
    if env_kind == "lan" and lan_ips:
        print("  Open Kobo's Browser on your tablet and type (plain http://):")
        for ip in lan_ips:
            print(f"\n      http://{ip}:{bound_port}")
        print("")
    elif env_kind == "wsl":
        print("  [WSL2 Detected] Run `tools\\serve-over-wifi.bat` directly in Windows")
        print("  so Windows Firewall & Wi-Fi adapter routing work automatically!")
    else:
        print(f"  Listening on http://0.0.0.0:{bound_port} (Cloud Preview Mode active)")
    print("=" * 70, flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
