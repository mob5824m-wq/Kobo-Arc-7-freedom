#!/usr/bin/env python3
"""
Kobo Arc 7 Freedom v2.2.0 — USB / Wi-Fi Extended Second Display & Macro Deck Host Sender

Usage (USB — fastest, low latency):
    adb forward tcp:18900 tcp:18900
    python3 Start-Kobo-Second-Screen.py 127.0.0.1

Usage (Wi-Fi):
    python3 Start-Kobo-Second-Screen.py <kobo-ip-shown-on-tablet>

Streams compressed 1280x800 desktop frames to `Arc7 Second Screen` on port 18900
and receives real-time touch input (`TOUCH x y`) & 12-key Macro Deck commands.
On Linux, install `xdotool` to inject touch and keyboard input. If it is missing, the
picture stream continues and this client prints one clear warning instead of crashing.
"""
import io
import math
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time

_WARNING_LOCK = threading.Lock()
_INPUT_WARNING_SHOWN = False


def warn_input_unavailable(reason: str) -> None:
    global _INPUT_WARNING_SHOWN
    with _WARNING_LOCK:
        if _INPUT_WARNING_SHOWN:
            return
        _INPUT_WARNING_SHOWN = True
    print(
        "[Input] Desktop clicks/keys are disabled: " + reason
        + ". On Debian/Ubuntu install with `sudo apt install xdotool`; "
        + "the second-screen image stream will continue.",
        flush=True,
    )


def run_xdotool(args) -> bool:
    executable = shutil.which("xdotool")
    if not executable:
        warn_input_unavailable("xdotool was not found on PATH")
        return False
    try:
        result = subprocess.run(
            [executable] + list(args),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=2.0,
        )
        if result.returncode != 0:
            warn_input_unavailable("xdotool could not inject input (check the active desktop session)")
            return False
        return True
    except (OSError, subprocess.SubprocessError) as exc:
        warn_input_unavailable("xdotool could not run: " + str(exc))
        return False


def handle_incoming_events(sock: socket.socket, screen_size=(1920, 1080)) -> None:
    buf = b""
    while True:
        try:
            chunk = sock.recv(1024)
        except OSError as exc:
            print(f"[Input] Connection closed while reading events: {exc}", flush=True)
            break
        if not chunk:
            break
        buf += chunk
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            msg = line.decode("utf-8", errors="ignore").strip()
            if not msg:
                continue
            print(f"[Kobo Input] {msg}", flush=True)
            if msg.startswith("TOUCH "):
                parts = msg.split()
                if len(parts) < 4 or parts[3] != "0":
                    continue
                try:
                    nx = float(parts[1])
                    ny = float(parts[2])
                    if not math.isfinite(nx) or not math.isfinite(ny):
                        continue
                except (TypeError, ValueError):
                    continue
                width = max(1, int(screen_size[0]))
                height = max(1, int(screen_size[1]))
                x = int(max(0.0, min(1.0, nx)) * (width - 1))
                y = int(max(0.0, min(1.0, ny)) * (height - 1))
                run_xdotool(["mousemove", str(x), str(y), "click", "1"])
            elif msg.startswith("MACRO "):
                cmd = msg.split(" ", 1)[1].strip()
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
                    run_xdotool(["key", key_map[cmd]])


def main() -> None:
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 18900
    if host in ("127.0.0.1", "localhost"):
        adb = shutil.which("adb")
        if adb:
            try:
                result = subprocess.run(
                    [adb, "forward", f"tcp:{port}", f"tcp:{port}"],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.PIPE,
                    check=False,
                    timeout=4.0,
                    text=True,
                )
                if result.returncode != 0:
                    print("[USB] adb forward failed: " + (result.stderr or "check USB debugging and the device"), flush=True)
            except (OSError, subprocess.SubprocessError) as exc:
                print(f"[USB] Could not run adb forward: {exc}", flush=True)
        else:
            print("[USB] adb was not found on PATH; for Wi-Fi pass the tablet's IP instead.", flush=True)

    from PIL import ImageGrab

    try:
        screen_size = ImageGrab.grab().size
    except Exception:
        screen_size = (1920, 1080)
    if not shutil.which("xdotool"):
        warn_input_unavailable("xdotool was not found on PATH")

    print(f"Connecting to Kobo Arc 7 Second Screen at {host}:{port} ...", flush=True)
    try:
        with socket.create_connection((host, port), timeout=10) as sock:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            threading.Thread(
                target=handle_incoming_events,
                args=(sock, screen_size),
                daemon=True,
            ).start()
            print("Connected! Streaming live 1280x800 frames to Kobo Arc 7 (Ctrl+C to stop)...", flush=True)
            while True:
                img = ImageGrab.grab().resize((1280, 800))
                out = io.BytesIO()
                img.convert("RGB").save(out, format="JPEG", quality=68)
                payload = out.getvalue()
                sock.sendall(struct.pack(">I", len(payload)) + payload)
                time.sleep(0.05)
    except (OSError, ConnectionError) as exc:
        print(f"Could not connect to the Kobo second-screen service at {host}:{port}: {exc}", flush=True)
        print("Check that Second Screen is open, the IP/port is correct, and the PC firewall allows the connection.", flush=True)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
