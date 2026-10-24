#!/usr/bin/env bash
# ==============================================================================
# Kobo Arc 7 Freedom v2.0.0 — Chromebook & Linux Wired USB Internet Bridge
#
# Gives any Kobo Arc 7 / Arc 7HD (Android 4.0.3 – 4.2.2) high-speed WIRED USB
# Internet straight through your Chromebook (Linux / Crostini), Linux laptop,
# or macOS machine — EVEN WHEN KOBO WI-FI IS OFF OR ON WPA3 / CAPTIVE NETWORKS!
#
# HOW IT WORKS ON ANDROID 4.2.2 (WHERE `adb reverse` DOES NOT EXIST!):
#   Android added `adb reverse` in Android 5.0 (API 21), so older scripts break
#   on Kobo Arc 7's Android 4.2.2 `adbd`. Instead, this bridge uses universal
#   `adb forward tcp:18880 tcp:18880` and maintains a pool of persistent warm
#   reverse USB streams into `Arc7ChromebookUsbService` (`127.0.0.1:18880`) on
#   your Kobo! Kobo dispatches outbound TCP + TLS 1.3 (`libarc7tls.so`) + DNS
#   requests straight back up those pre-opened USB streams at full USB 2.0 speed!
# ==============================================================================
set -euo pipefail

echo "======================================================================"
echo "  Kobo Arc 7 Freedom v2.0.0 — Chromebook Wired USB Internet Bridge"
echo "======================================================================"

if ! command -v adb >/dev/null 2>&1; then
    echo "[1/3] 'adb' not installed yet in Chromebook Linux — installing automatically..."
    if command -v apt-get >/dev/null 2>&1; then
        sudo apt-get update && sudo apt-get install -y adb python3
    else
        echo "ERROR: Please install Android 'adb' and 'python3' first."
        exit 1
    fi
fi

PY_BIN="$(command -v python3 || command -v python)"
if [ -z "$PY_BIN" ]; then
    echo "ERROR: Python 3 is required."
    exit 1
fi

PORT="${ARC7_USB_PORT:-18880}"

if [ "${ARC7_SELFTEST_DIRECT:-0}" != "1" ]; then
    echo "[1/3] Waiting for Kobo Arc 7 over USB cable (make sure USB Debugging is connected in ChromeOS Linux settings)..."
    adb start-server >/dev/null 2>&1 || true
    adb wait-for-device
    SERIAL="$(adb get-serialno 2>/dev/null || echo 'USB')"
    echo "      Connected to Kobo device ($SERIAL)!"

    echo "[2/3] Starting Arc7 Wired Internet Service on Kobo & opening USB tunnel (tcp:$PORT)..."
    adb shell "am startservice -a ca.arc7freedom.action.START_USB_INTERNET -n ca.arc7freedom.app/.Arc7ChromebookUsbService >/dev/null 2>&1 || am broadcast -a ca.arc7freedom.action.START_USB_INTERNET >/dev/null 2>&1 || true" || true
    adb forward "tcp:${PORT}" "tcp:${PORT}"
fi

echo "[3/3] Wired USB Internet is LIVE! Keep this window open while browsing on your Kobo."
echo "      Press Ctrl+C anytime to disconnect cleanly."
echo ""

exec "$PY_BIN" - "$PORT" << 'PYEOF'
import select
import socket
import struct
import subprocess
import sys
import threading
import time

USB_PORT = int(sys.argv[1])
POOL_SIZE = 10
SELFTEST = False
if "--once" in sys.argv:
    SELFTEST = True

active_lock = threading.Lock()
idle_workers = 0
stop_flag = False


def bridge_bidirectional(a: socket.socket, b: socket.socket) -> None:
    try:
        fds = [a, b]
        while True:
            r, _, x = select.select(fds, [], fds, 120.0)
            if x or not r:
                break
            for s in r:
                peer = b if s is a else a
                data = s.recv(65536)
                if not data:
                    return
                peer.sendall(data)
    except Exception:
        pass
    finally:
        for s in (a, b):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
            try:
                s.close()
            except Exception:
                pass


def handle_tunnel_slot() -> None:
    global idle_workers
    usb = None
    remote = None
    counted_idle = False
    try:
        usb = socket.create_connection(("127.0.0.1", USB_PORT), timeout=5.0)
        usb.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        usb.sendall(b"ARC7USB1 READY\n")
        with active_lock:
            idle_workers += 1
            counted_idle = True

        # Wait until Kobo assigns a real outbound request to this warm USB socket
        buf = b""
        usb.settimeout(45.0)
        while b"\n" not in buf:
            try:
                chunk = usb.recv(4096)
            except socket.timeout:
                # Send keepalive ping cycle by re-cycling idle slot cleanly
                return
            if not chunk:
                return
            buf += chunk
            if len(buf) > 8192:
                return

        with active_lock:
            if counted_idle:
                idle_workers -= 1
                counted_idle = False

        line, remainder = buf.split(b"\n", 1)
        cmd = line.decode("utf-8", "replace").strip()

        if cmd == "PING":
            usb.sendall(b"PONG\n")
            return

        if cmd.startswith("RESOLVE "):
            host = cmd[8:].strip()
            try:
                ip = socket.gethostbyname(host)
                usb.sendall(f"IP {ip}\n".encode("utf-8"))
            except Exception as exc:
                usb.sendall(f"ERR {exc}\n".encode("utf-8"))
            return

        if cmd.startswith("DNSRAW "):
            nbytes = int(cmd[7:].strip())
            while len(remainder) < nbytes:
                more = usb.recv(4096)
                if not more:
                    return
                remainder += more
            qpkt = remainder[:nbytes]
            udps = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            udps.settimeout(2.5)
            try:
                udps.sendto(qpkt, ("1.1.1.1", 53))
                ans, _ = udps.recvfrom(4096)
                usb.sendall(struct.pack(">H", len(ans)) + ans)
            except Exception:
                usb.sendall(b"\x00\x00")
            finally:
                udps.close()
            return

        if cmd.startswith("CONNECT "):
            target = cmd[8:].strip()
            host, port_s = target.rsplit(":", 1)
            port = int(port_s)
            remote = socket.create_connection((host, port), timeout=12.0)
            remote.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            usb.sendall(b"OK\n")
            if remainder:
                remote.sendall(remainder)
            print(f"  [USB -> NET] {host}:{port}")
            usb.settimeout(None)
            remote.settimeout(None)
            bridge_bidirectional(usb, remote)
            usb = None
            remote = None
            return
    except Exception:
        time.sleep(0.35)
    finally:
        with active_lock:
            if counted_idle:
                idle_workers -= 1
        for s in (usb, remote):
            if s is not None:
                try:
                    s.close()
                except Exception:
                    pass


def ensure_adb_forward_alive() -> None:
    while not stop_flag:
        time.sleep(6.0)
        try:
            out = subprocess.run(["adb", "forward", "--list"], capture_output=True, text=True, timeout=4)
            if f"tcp:{USB_PORT}" not in (out.stdout or ""):
                subprocess.run(["adb", "forward", f"tcp:{USB_PORT}", f"tcp:{USB_PORT}"],
                               capture_output=True, timeout=4)
        except Exception:
            pass


if __name__ == "__main__":
    t_watch = threading.Thread(target=ensure_adb_forward_alive, daemon=True)
    t_watch.start()
    try:
        while not stop_flag:
            with active_lock:
                deficit = POOL_SIZE - idle_workers
            for _ in range(max(0, deficit)):
                threading.Thread(target=handle_tunnel_slot, daemon=True).start()
            time.sleep(0.08)
    except KeyboardInterrupt:
        stop_flag = True
        print("\nDisconnected Chromebook USB Internet cleanly.")
PYEOF
