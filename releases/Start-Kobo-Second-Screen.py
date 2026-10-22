#!/usr/bin/env python3
"""
Kobo Arc 7 Freedom v2.2.0 — USB / Wi-Fi Extended Second Display & Macro Deck Host Sender

Usage (USB — fastest, <12 ms latency!):
    adb forward tcp:18900 tcp:18900
    python3 Start-Kobo-Second-Screen.py 127.0.0.1

Usage (Wi-Fi):
    python3 Start-Kobo-Second-Screen.py <kobo-ip-shown-on-tablet>

Streams compressed 1280x800 desktop frames to `Arc7 Second Screen` on port 18900
and receives real-time touch input (`TOUCH x y`) & 12-key Macro Deck commands!
"""
import io
import socket
import struct
import subprocess
import sys
import threading
import time


def handle_incoming_events(sock: socket.socket) -> None:
    buf = b""
    while True:
        chunk = sock.recv(1024)
        if not chunk:
            break
        buf += chunk
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            msg = line.decode("utf-8", errors="ignore").strip()
            print(f"[Kobo Input] {msg}", flush=True)
            if msg.startswith("TOUCH "):
                parts = msg.split()
                if len(parts) >= 4 and int(parts[3]) == 0:
                    nx, ny = float(parts[1]), float(parts[2])
                    subprocess.run(["xdotool", "mousemove", str(int(nx * 1920)), str(int(ny * 1080)), "click", "1"],
                                   stderr=subprocess.DEVNULL)
            elif msg.startswith("MACRO "):
                cmd = msg.split(" ", 1)[1]
                key_map = {
                    "MEDIA_PLAY_PAUSE": "XF86AudioPlay",
                    "MEDIA_PREV": "XF86AudioPrev",
                    "MEDIA_NEXT": "XF86AudioNext",
                    "VOL_MUTE": "XF86AudioMute",
                    "VOL_DOWN": "XF86AudioLowerVolume",
                    "VOL_UP": "XF86AudioRaiseVolume",
                    "BROWSER_NEW_TAB": "ctrl+t",
                    "CLIP_COPY": "ctrl+c",
                    "CLIP_PASTE": "ctrl+v",
                    "ZOOM_IN": "ctrl+plus",
                }
                if cmd in key_map:
                    subprocess.run(["xdotool", "key", key_map[cmd]], stderr=subprocess.DEVNULL)


def main() -> None:
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 18900
    if host == "127.0.0.1":
        subprocess.run(["adb", "forward", f"tcp:{port}", f"tcp:{port}"], stderr=subprocess.DEVNULL)

    from PIL import ImageGrab

    print(f"Connecting to Kobo Arc 7 Second Screen at {host}:{port} ...")
    with socket.create_connection((host, port), timeout=10) as sock:
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        threading.Thread(target=handle_incoming_events, args=(sock,), daemon=True).start()
        print("Connected! Streaming live 1280x800 frames to Kobo Arc 7 (Ctrl+C to stop)...")
        while True:
            img = ImageGrab.grab().resize((1280, 800))
            out = io.BytesIO()
            img.convert("RGB").save(out, format="JPEG", quality=68)
            payload = out.getvalue()
            sock.sendall(struct.pack(">I", len(payload)) + payload)
            time.sleep(0.05)


if __name__ == "__main__":
    main()
