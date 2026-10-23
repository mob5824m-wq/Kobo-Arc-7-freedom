#!/usr/bin/env python3
"""
Zero-Cable, Zero-Driver, Zero-SSL Local Home Wi-Fi & Live Web Installer Server
for Kobo Arc 7 & Kobo Arc 7HD (`tools/serve-over-wifi.py` — `v2.5.0`).

Fixes all common home-network, OS firewall, WSL2/container, and 2013 Android 4.2
Stock Browser (`DownloadManager`) failure modes:
  1. Multi-interface LAN IPv4 discovery (prioritizing real Wi-Fi/Ethernet adapters
     and warning clearly if run inside WSL2, VPN, or a cloud sandbox container).
  2. Full HTTP/1.1 + `HEAD` + `Range` (`206 Partial Content`) + `SO_REUSEADDR` +
     `Content-Disposition` attachment headers required by Android 4.2 DownloadManager.
  3. Instant `<1ms` fast-reject of TLS `ClientHello` (`0x16`/`0x80`) so modern Chrome/
     Edge/Safari browsers with HTTPS-First Mode immediately fall back to `http://`.
  4. Native inline `image/png` serving (`inline=True`, no `attachment` header, zero stale
     `DOCS_DIR` references!) for `/logo-banner.png`, `/icons/*.png`, and all image files
     in `releases/` — plus base64 data-URI `onerror` fallbacks so 100% of images render
     crisply on `http://<lan-ip>:8000`, `https://` cloud previews, AND offline `file://`!

Usage:
  python3 tools/serve-over-wifi.py              # Start server on 0.0.0.0:8000
  python3 tools/serve-over-wifi.py 8080         # Custom port
  python3 tools/serve-over-wifi.py --write-static  # Generate static releases/index.html
"""
import base64
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
TOOLS_DIR = os.path.join(ROOT_DIR, "tools")
APP_ICON_DIR = os.path.join(ROOT_DIR, "app", "src", "main", "res", "drawable-xhdpi")
DEFAULT_PORT = 8000
UPGRADER_APK_NAME = "Upgrade-My-Kobo-To-v2.5.0.apk"
GITHUB_REPO = "mob5824m-wq/Kobo-Arc-7-freedom"
GITHUB_LATEST_BASE = f"https://github.com/{GITHUB_REPO}/releases/latest/download"
GITHUB_RAW_ROOT = f"https://raw.githubusercontent.com/{GITHUB_REPO}/main"

IMAGE_MIMES = {
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".gif": "image/gif",
    ".webp": "image/webp",
    ".svg": "image/svg+xml",
    ".ico": "image/x-icon",
    ".bmp": "image/bmp",
}

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


def image_mime_for_name(fname: str) -> str:
    ext = os.path.splitext(fname.lower())[1]
    return IMAGE_MIMES.get(ext, "")


def resolve_banner_path() -> str:
    for cand in (
        os.path.join(RELEASES_DIR, "logo-banner.png"),
        os.path.join(ROOT_DIR, "releases", "logo-banner.png"),
        os.path.join(APP_ICON_DIR, "arc7_logo_header.png"),
    ):
        if os.path.isfile(cand):
            return cand
    return ""


def resolve_icon_path(icon_name: str) -> str:
    clean = os.path.basename(icon_name)
    for cand in (
        os.path.join(APP_ICON_DIR, clean),
        os.path.join(ROOT_DIR, "launcher-android4", "res", "drawable-xhdpi", clean),
        os.path.join(RELEASES_DIR, clean),
    ):
        if os.path.isfile(cand):
            return cand
    return ""


def _data_uri_or_empty(path: str, max_bytes: int = 98304) -> str:
    if not path or not os.path.isfile(path):
        return ""
    try:
        sz = os.path.getsize(path)
        if sz <= 0 or sz > max_bytes:
            return ""
        mime = image_mime_for_name(path) or "image/png"
        with open(path, "rb") as fp:
            b64 = base64.b64encode(fp.read()).decode("ascii")
        return f"data:{mime};base64,{b64}"
    except OSError:
        return ""


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
        return f"./{urllib.parse.quote(fname)}"

    def _mirror_href(fname: str) -> str:
        return f"{GITHUB_LATEST_BASE}/{urllib.parse.quote(fname)}"

    def _icon_img(icon_file: str, alt: str, px: int = 38) -> str:
        disk_path = resolve_icon_path(icon_file)
        fallback_uri = _data_uri_or_empty(disk_path, max_bytes=32768)
        src = f"./icons/{urllib.parse.quote(icon_file)}"
        err_attr = (
            f" onerror=\"this.onerror=null;this.src='{fallback_uri}';\""
            if fallback_uri
            else ""
        )
        return (
            f"<img src='{src}' alt='{html.escape(alt)}' width='{px}' height='{px}'"
            f" style='width:{px}px;height:{px}px;vertical-align:middle;margin-right:10px;border-radius:9px;'{err_attr}>"
        )

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
        if fname == UPGRADER_APK_NAME:
            ico = _icon_img("ic_apk_installer.png", "System Upgrader")
            upgrader_html = (
                f"<div class='dl-group' style='border:1px solid #10b981;padding:12px;border-radius:12px;background:#071915;margin-bottom:12px;'>"
                f"<div style='color:#6ee7b7;font-size:13px;font-weight:bold;margin-bottom:8px;'>"
                f"&#9889; 1-TAP OVER-THE-AIR UPGRADE &amp; ROOT INSTALLER (v2.5.0 — playable 2026 DASH streams + TRULY SEPARATE YouTube / YouTube Music apps + Standby Audio + Tailscale Google Auth + Fixed F-Droid v1303):</div>"
                f"<a class='btn primary' href='{link}' onclick='onStartDl(\"{UPGRADER_APK_NAME}\")'>"
                f"{ico}<span>&#11015; 1-Tap OTA Root &amp; Upgrade — {UPGRADER_APK_NAME} ({size_txt})</span></a>"
                + (
                    f"<a class='btn subtle' href='./raw/{urllib.parse.quote(fname)}' onclick='onStartDl(\"{UPGRADER_APK_NAME}\")'>"
                    f"&#128295; Alternate Direct Binary Stream for Upgrader (tap if Kobo Browser says &ldquo;Cannot download&rdquo;)</a>"
                    if not static_mode
                    else f"<a class='btn subtle' href='{mirror}'>&#9729; GitHub Releases Download — {UPGRADER_APK_NAME}</a>"
                )
                + "</div>"
            )
        elif fname == "KoboArc7Freedom.apk":
            ico = _icon_img("ic_launcher.png", "Arc7 Freedom")
            main_html = (
                f"<div class='dl-group'>"
                f"<a class='btn primary' href='{link}' onclick='onStartDl(\"KoboArc7Freedom.apk\")'>"
                f"{ico}<span>&#11015; Install / Direct Update: KoboArc7Freedom.apk v2.5.0 ({size_txt}) — separate YouTube &amp; YouTube Music apps + Tailscale Google Auth + Launcher v55 + VLC</span></a>"
                + (
                    f"<a class='btn subtle' href='./raw/{urllib.parse.quote(fname)}' onclick='onStartDl(\"KoboArc7Freedom.apk\")'>"
                    f"&#128295; Alternate Direct Binary Stream (tap this if Kobo Browser says &ldquo;Cannot download&rdquo;)</a>"
                    if not static_mode
                    else f"<a class='btn subtle' href='{mirror}'>&#9729; GitHub Releases Download — KoboArc7Freedom.apk</a>"
                )
                + "</div>"
            )
        elif fname == "YouTube.apk":
            ico = _icon_img("ic_youtube.png", "YouTube", 32)
            apk_buttons.append(
                f"<a class='btn secondary' href='{link}' onclick='onStartDl(\"YouTube.apk\")'>"
                f"{ico}<span>&#11015; Download the SEPARATE YouTube app (`ca.arc7freedom.youtube`, v2.5.0 — 2026 DASH remux playback) — YouTube.apk ({size_txt})</span></a>"
            )
        elif fname == "YouTubeMusic.apk":
            ico = _icon_img("ic_ytmusic.png", "YouTube Music", 32)
            apk_buttons.append(
                f"<a class='btn secondary' href='{link}' onclick='onStartDl(\"YouTubeMusic.apk\")'>"
                f"{ico}<span>&#11015; Download the SEPARATE YouTube Music app (`ca.arc7freedom.ytmusic`, v2.5.0 — screen-off AAC) — YouTubeMusic.apk ({size_txt})</span></a>"
            )
        elif fname == "VLC.apk":
            ico = _icon_img("ic_vlc.png", "VLC", 32)
            apk_buttons.append(
                f"<a class='btn secondary' href='{link}' onclick='onStartDl(\"VLC.apk\")'>"
                f"{ico}<span>&#11015; Download Standalone VLC Media Player (Android 4.2 ARMv7) — VLC.apk ({size_txt})</span></a>"
            )
        elif fname == "AuroraStore.apk":
            ico = _icon_img("ic_aurora.png", "Aurora Store", 32)
            aurora_html = (
                f"<a class='btn secondary' href='{link}' onclick='onStartDl(\"AuroraStore.apk\")'>"
                f"{ico}<span>&#11015; Download Standalone Aurora Store v4.8.5 (Verified Android 4.2 APKs + Auto JAR v1 Signer) — AuroraStore.apk ({size_txt})</span></a>"
            )
        elif fname == "FDroidClassicLegacy.apk":
            ico = _icon_img("ic_fdroid.png", "F-Droid", 32)
            apk_buttons.append(
                f"<a class='btn secondary' href='{link}' onclick='onStartDl(\"FDroidClassicLegacy.apk\")'>"
                f"{ico}<span>&#11015; Download Patched F-Droid Classic v1303 (Android 4.2 SHA-256 Index &amp; APK Fix + TLS 1.3 Bridge) — FDroidClassicLegacy.apk ({size_txt})</span></a>"
            )
        elif fname == "VanillaAndroid4Home.apk":
            ico = _icon_img("ic_wallpaper.png", "Home", 32)
            apk_buttons.append(
                f"<a class='btn secondary' href='{link}' onclick='onStartDl(\"VanillaAndroid4Home.apk\")'>"
                f"{ico}<span>&#11015; Download Home Launcher v55 (Long-Press + 1-Tap Widget Removal) — VanillaAndroid4Home.apk ({size_txt})</span></a>"
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

    banner_disk = resolve_banner_path()
    banner_b64 = _data_uri_or_empty(banner_disk, max_bytes=98304)
    logo_src = "./logo-banner.png" if banner_disk else ""
    banner_err_attr = (
        f" onerror=\"this.onerror=null;this.src='{banner_b64}';\""
        if banner_b64
        else ""
    )

    if env_kind == "lan" and lan_ips:
        urls_html = "".join(
            f"<div class='url-pill'>http://{html.escape(ip)}:{port}</div>" for ip in lan_ips
        )
        top_banner = (
            "<div class='banner ok'>"
            "<b>&#9989; Home Wi-Fi Sideload Server ACTIVE on Your Network!</b><br>"
            "Open Kobo's Browser on your Kobo Arc 7 and type this exact address "
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
            f"<b>{UPGRADER_APK_NAME}</b> &rarr; <b>Install</b>!"
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
        f"<img src='{logo_src}' alt='Kobo Arc 7 Freedom'"
        f" style='max-width:100%;max-height:118px;border-radius:10px;display:inline-block;'{banner_err_attr}>"
        f"</div>"
        if logo_src
        else ""
    )

    featured_apps_strip = (
        "<div style='display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:10px 0 14px 0;"
        "padding:10px 12px;background:#0d1622;border:1px solid #1e293b;border-radius:10px;'>"
        + _icon_img("ic_youtube.png", "YouTube", 30)
        + "<span style='font-size:13px;color:#e2e8f0;margin-right:10px;'><b>YouTube</b> (Morphe)</span>"
        + _icon_img("ic_ytmusic.png", "YouTube Music", 30)
        + "<span style='font-size:13px;color:#e2e8f0;margin-right:10px;'><b>YouTube Music</b> (Morphe)</span>"
        + _icon_img("ic_vpn.png", "Wi-Fi & VPN", 30)
        + "<span style='font-size:13px;color:#e2e8f0;margin-right:10px;'><b>Official Tailscale Google Auth</b></span>"
        + _icon_img("ic_clock.png", "Clock", 30)
        + "<span style='font-size:13px;color:#e2e8f0;'><b>Clock Standby Now Playing</b></span>"
        "</div>"
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
        "<h1>Kobo Arc 7 Freedom v2.5.0 — Over-The-Air Rooting &amp; Local Web Installer Portal</h1>"
        "<p>Live Direct OTA APK Downloads (Zero Redirects!) &bull; YouTube &amp; YouTube Music (Morphe Edition) &bull; "
        "Official Tailscale Google Sign-In &bull; Universal Standby Audio &bull; Fixed F-Droid v1303 &bull; VLC &amp; Aurora Store v4.8.5</p>"
        + featured_apps_strip
        + live_progress_widget
        + "".join(apk_buttons)
        + ("".join(other_buttons) if other_buttons else "")
        + "</div>"
        + top_banner
        + upload_section
        + "<div class='card'>"
        "<h2>&#128187; 1-Click Direct Laptop Backup &amp; Restore Over USB ADB (0 MB Tablet Space!)</h2>"
        "<p>Stream bit-for-bit flash partitions (<code>boot</code>, <code>recovery</code>, <code>nvram</code> Wi-Fi MAC calibration, "
        "<code>uboot</code>, <code>system</code> OS) + your Books, Music &amp; Photos straight over USB onto your laptop — with cryptographic SHA-256 verification:</p>"
        f"<a class='btn win' href='{adb_bat_href}'>&#128187; Download 1-Click Windows USB ADB Backup Tool (Backup-Kobo-To-Laptop-ADB.bat — Auto-Installs ADB!)</a>"
        f"<a class='btn mac' href='{adb_sh_href}'>&#128187; Download 1-Click macOS / Linux USB ADB Backup Tool (Backup-Kobo-To-Laptop-ADB.sh)</a>"
        "<p><i>Or with any laptop web browser:</i> plug USB in, run <code>adb forward tcp:18777 tcp:18777</code>, and open <code>http://127.0.0.1:18777</code>!</p>"
        "</div>"
        + "<div class='card'>"
        "<h2>&#128246; Method A — 1-Click Home Wi-Fi Installer (No USB Cable, No MicroSD, No Python Needed!)</h2>"
        "<p>Download &amp; run the 1-click launcher on any computer connected to the <b>same Wi-Fi</b> as your Kobo Arc 7. "
        "It automatically opens your firewall, displays your exact <code>http://192.168.x.x:8000</code> address, "
        "and serves all images, icons &amp; APKs cleanly over zero-SSL HTTP straight to Kobo's Browser:</p>"
        f"<a class='btn win' href='{bat_href}'>&#129695; Download 1-Click Windows Home Wi-Fi Server (Start-Kobo-WiFi-Server.bat — Zero Python Needed!)</a>"
        f"<a class='btn mac' href='{sh_href}'>&#128039; Download 1-Click macOS / Linux Home Wi-Fi Server (Start-Kobo-WiFi-Server.sh)</a>"
        "<ol>"
        "<li>On your Kobo Arc 7, enable <b>Settings &rarr; Security &rarr; Unknown sources</b>.</li>"
        "<li>Double-click <b>Start-Kobo-WiFi-Server.bat</b> on Windows (or run <code>bash Start-Kobo-WiFi-Server.sh</code> on Mac/Linux).</li>"
        "<li>Open Browser on your Kobo and enter the shown <code>http://192.168.x.x:8000</code> address (start with <code>http://</code>!).</li>"
        f"<li>Tap <b>{UPGRADER_APK_NAME}</b> (or <b>KoboArc7Freedom.apk</b>), then swipe down the <b>top-left notification shade</b> and tap to install!</li>"
        "</ol>"
        "</div>"
        "<div class='card'>"
        "<h2>&#128190; Method B — MicroSD Card Trick (Works Completely Offline!)</h2>"
        "<ol>"
        "<li>Click <b>Download KoboArc7Freedom.apk</b> at the top of this page and copy it to the root of any FAT32 microSD card.</li>"
        "<li>Insert the microSD card into your Kobo Arc 7, open Browser, and type:<br>"
        "<code>file:///storage/sdcard1/KoboArc7Freedom.apk</code></li>"
        "<li>Tap <b>Install</b> — you're in!</li>"
        "</ol>"
        "</div>"
        "<div class='card'>"
        "<h2>&#128193; Using On-Tablet Wireless Drive (<code>http://&lt;tablet-ip&gt;:8080</code>)</h2>"
        "<p>Once <b>KoboArc7Freedom.apk</b> is installed on your tablet, open <b>Tab 5 (TOOLS) &rarr; Wireless Drive</b>:</p>"
        "<ul>"
        "<li>Tapping <b>Wireless Drive</b> starts the foreground server <b>immediately</b> and gives you a direct "
        "1-step URL (<code>http://192.168.x.x:8080/?pin=XXXX</code>) plus a <b>Disable PIN</b> toggle!</li>"
        "<li>Always include <code>http://</code> in Chrome/Safari/Edge so HTTPS-First mode connects instantaneously.</li>"
        "</ul></div></div>"
        + js_script
        + "</body></html>"
    )


class Arc7HTTPServer(http.server.ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True

    def verify_request(self, request, client_address):
        """
        Instantly reject accidental TLS `ClientHello` (`0x16` / SSLv2 `0x80`) in `<1ms`
        so Chrome / Safari / Edge HTTPS-First Mode immediately falls back to `http://`!
        """
        try:
            request.settimeout(3.0)
            peek = request.recv(1, socket.MSG_PEEK)
            if not peek or peek[0] in (0x16, 0x80):
                return False
            request.settimeout(60.0)
        except Exception:
            pass
        return True


class Arc7Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_port_num = DEFAULT_PORT

    def log_message(self, fmt, *args):
        sys.stdout.write("[Wi-Fi HTTP] " + (fmt % args) + "\n")
        sys.stdout.flush()

    def _send_common_headers(self, mime: str, length: int, extra: dict = None) -> None:
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(length))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "public, max-age=120" if mime.startswith("image/") else "no-cache")
        if extra:
            for k, v in extra.items():
                self.send_header(k, v)
        self.send_header("Connection", "close")
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
                fav = resolve_icon_path("ic_launcher.png")
                if fav:
                    self._stream_file(fav, "image/png", head_only, inline=True)
                    return
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

            if req_path in ("/logo-banner.png", "/releases/logo-banner.png"):
                banner_file = resolve_banner_path()
                if banner_file:
                    self._stream_file(banner_file, "image/png", head_only, inline=True)
                    return

            if req_path.startswith("/icons/"):
                icon_file = resolve_icon_path(urllib.parse.unquote(req_path[len("/icons/"):]))
                if icon_file:
                    self._stream_file(icon_file, image_mime_for_name(icon_file) or "image/png", head_only, inline=True)
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
            if not os.path.isfile(target) and image_mime_for_name(decoded):
                icon_fallback = resolve_icon_path(decoded)
                if icon_fallback:
                    target = icon_fallback
            if decoded and os.path.isfile(target):
                img_mime = image_mime_for_name(decoded)
                if force_raw:
                    mime = "application/octet-stream"
                    inline_flag = False
                elif img_mime:
                    mime = img_mime
                    inline_flag = True
                elif decoded.lower().endswith(".apk"):
                    mime = "application/vnd.android.package-archive"
                    inline_flag = False
                elif decoded.lower().endswith(".txt"):
                    mime = "text/plain; charset=utf-8"
                    inline_flag = True
                elif decoded.lower().endswith(".html"):
                    mime = "text/html; charset=utf-8"
                    inline_flag = True
                else:
                    mime = "application/octet-stream"
                    inline_flag = False
                self._stream_file(target, mime, head_only, download_name=decoded, inline=inline_flag)
                return

            msg = b"File not found. Return to / for the Kobo Arc 7 Installer Portal."
            self.send_response(404)
            self._send_common_headers("text/plain; charset=utf-8", len(msg))
            if not head_only:
                self.wfile.write(msg)
        except (BrokenPipeError, ConnectionResetError):
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
    os.makedirs(RELEASES_DIR, exist_ok=True)
    icons_dir = os.path.join(RELEASES_DIR, "icons")
    os.makedirs(icons_dir, exist_ok=True)
    shutil.copyfile(os.path.join(TOOLS_DIR, "serve-over-wifi.bat"), os.path.join(RELEASES_DIR, "Start-Kobo-WiFi-Server.bat"))
    shutil.copyfile(os.path.join(TOOLS_DIR, "serve-over-wifi.sh"), os.path.join(RELEASES_DIR, "Start-Kobo-WiFi-Server.sh"))
    os.chmod(os.path.join(RELEASES_DIR, "Start-Kobo-WiFi-Server.sh"), 0o755)

    rel_html = render_portal_html(static_mode=True)
    for icon_file in sorted(set(re.findall(r"\./icons/([A-Za-z0-9_.-]+)", rel_html))):
        src_p = resolve_icon_path(icon_file)
        if src_p and os.path.isfile(src_p):
            shutil.copyfile(src_p, os.path.join(icons_dir, icon_file))
    with open(os.path.join(RELEASES_DIR, "index.html"), "w", encoding="utf-8") as f:
        f.write(rel_html)
    print(f"Generated static local Wi-Fi portal: releases/index.html (+ {len(os.listdir(icons_dir))} squircle PNGs in releases/icons/)")


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
    print("  Kobo Arc 7 Freedom v2.5.0 — Zero-SSL Local Web Installer READY!")
    if env_kind == "lan" and lan_ips:
        print("  Open Browser on your Kobo Arc 7 and type (plain http://):")
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
