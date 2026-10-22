#!/usr/bin/env python3
"""
Arc7 Home-PC Port-443 HTTPS Stealth Tunnel & SOCKS5 Relay (`v2.3.0`)
====================================================================
Run this script on your Home PC (or a free cloud VM) to host your own personal
zero-cost VPN gateway for your Kobo Arc 7 (`Arc7 VPN & Tailscale`):

  - Speaks standard HTTP CONNECT (optionally wrapped in TLS on port 443 / 8443)
    AND standard RFC-1928 SOCKS5 on one lightweight zero-dependency Python server!
  - Pairs directly with `Arc7VpnService` (`/dev/tun` system-wide VPN on Android 4.2)
    so ALL apps on your Kobo route cleanly through your home residential connection,
    even on strict school networks that block UDP WireGuard/OpenVPN!
"""
import argparse
import base64
import select
import socket
import ssl
import struct
import threading


def pipe_sockets(a: socket.socket, b: socket.socket) -> None:
    try:
        while True:
            r, _, _ = select.select([a, b], [], [], 60.0)
            if not r:
                break
            for s in r:
                data = s.recv(16384)
                if not data:
                    return
                (b if s is a else a).sendall(data)
    except Exception:
        pass
    finally:
        for s in (a, b):
            try:
                s.close()
            except Exception:
                pass


def handle_client(cli: socket.socket, token: str) -> None:
    try:
        first = cli.recv(1)
        if not first:
            cli.close()
            return

        # 1. RFC 1928 SOCKS5 (works on both plain TCP and TLS 1.3 ssl.SSLSocket without MSG_PEEK!)
        if first[0] == 0x05:
            nm = cli.recv(1)
            methods = cli.recv(nm[0]) if nm else b""
            if token:
                if 0x02 not in methods:
                    cli.sendall(b"\x05\xff")
                    cli.close()
                    return
                cli.sendall(b"\x05\x02")
                ver_ulen = cli.recv(2)
                uname = cli.recv(ver_ulen[1]).decode("utf-8", "replace")
                plen = cli.recv(1)[0]
                upass = cli.recv(plen).decode("utf-8", "replace")
                if f"{uname}:{upass}" != token:
                    cli.sendall(b"\x01\x01")
                    cli.close()
                    return
                cli.sendall(b"\x01\x00")
            else:
                cli.sendall(b"\x05\x00")

            req = cli.recv(4)
            if len(req) < 4 or req[1] != 0x01:
                cli.close()
                return
            atyp = req[3]
            if atyp == 0x01:
                host = socket.inet_ntoa(cli.recv(4))
            elif atyp == 0x03:
                dlen = cli.recv(1)[0]
                host = cli.recv(dlen).decode("utf-8")
            else:
                cli.close()
                return
            port = struct.unpack("!H", cli.recv(2))[0]
            upstream = socket.create_connection((host, port), timeout=8.0)
            cli.sendall(b"\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00")
            pipe_sockets(cli, upstream)
            return

        # 2. HTTP CONNECT tunnel (prepend already-consumed `first` byte!)
        buf = first
        while b"\r\n\r\n" not in buf and len(buf) < 4096:
            chunk = cli.recv(1024)
            if not chunk:
                break
            buf += chunk
        text = buf.decode("utf-8", "replace")
        lines = text.split("\r\n")
        if not lines or not lines[0].upper().startswith("CONNECT "):
            cli.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nArc7 Personal Relay Ready\r\n")
            cli.close()
            return
        if token:
            expected = base64.b64encode(token.encode("utf-8")).decode("ascii")
            if f"Proxy-Authorization: Basic {expected}" not in text:
                cli.sendall(b"HTTP/1.1 407 Proxy Authentication Required\r\n\r\n")
                cli.close()
                return
        target = lines[0].split()[1]
        host, port_s = target.rsplit(":", 1)
        upstream = socket.create_connection((host, int(port_s)), timeout=8.0)
        cli.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        pipe_sockets(cli, upstream)
    except Exception:
        try:
            cli.close()
        except Exception:
            pass


def ensure_self_signed_tls(cert_dir: str):
    import os
    import subprocess
    os.makedirs(cert_dir, exist_ok=True)
    cert_p = os.path.join(cert_dir, "arc7-relay.crt")
    key_p = os.path.join(cert_dir, "arc7-relay.key")
    if os.path.isfile(cert_p) and os.path.isfile(key_p):
        return cert_p, key_p
    try:
        subprocess.run(
            [
                "openssl", "req", "-x509", "-newkey", "rsa:2048",
                "-keyout", key_p, "-out", cert_p,
                "-days", "3650", "-nodes", "-subj", "/CN=arc7-home-relay",
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return cert_p, key_p
    except Exception:
        import datetime
        from cryptography import x509
        from cryptography.x509.oid import NameOID
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        k = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "arc7-home-relay")])
        now = datetime.datetime.now(datetime.timezone.utc)
        c = (
            x509.CertificateBuilder()
            .subject_name(name)
            .issuer_name(name)
            .public_key(k.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=3650))
            .sign(k, hashes.SHA256())
        )
        with open(key_p, "wb") as kf:
            kf.write(k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL, serialization.NoEncryption()))
        with open(cert_p, "wb") as cf:
            cf.write(c.public_bytes(serialization.Encoding.PEM))
        return cert_p, key_p


def main() -> None:
    ap = argparse.ArgumentParser(description="Arc7 Personal School-Bypass HTTPS/SOCKS5 VPN Relay")
    ap.add_argument("--port", type=int, default=8443, help="Listen TCP port (use 443 for strict school firewalls)")
    ap.add_argument("--token", default="", help="Optional user:password auth secret")
    ap.add_argument("--cert", default="", help="Optional TLS PEM certificate path for HTTPS stealth mode")
    ap.add_argument("--key", default="", help="Optional TLS PEM private key path")
    ap.add_argument("--cert-dir", default="", help="Auto-generate/reuse self-signed TLS 1.3 cert in directory")
    ap.add_argument("--tls", action="store_true", help="Enable automatic TLS 1.3 stealth mode in ~/.arc7-relay")
    args = ap.parse_args()

    ssl_ctx = None
    if args.tls and not args.cert_dir and not (args.cert and args.key):
        import os
        args.cert_dir = os.path.expanduser("~/.arc7-relay")
    if args.cert_dir and not (args.cert and args.key):
        args.cert, args.key = ensure_self_signed_tls(args.cert_dir)
    if args.cert and args.key:
        ssl_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_ctx.load_cert_chain(args.cert, args.key)

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", args.port))
    srv.listen(64)
    print(f"[Arc7 VPN Relay] Listening on 0.0.0.0:{args.port} ({'TLS 1.3 Stealth' if ssl_ctx else 'SOCKS5 + HTTP CONNECT'})", flush=True)
    while True:
        c, _ = srv.accept()
        if ssl_ctx:
            try:
                c = ssl_ctx.wrap_socket(c, server_side=True)
            except Exception:
                c.close()
                continue
        threading.Thread(target=handle_client, args=(c, args.token), daemon=True).start()


if __name__ == "__main__":
    main()
