#!/usr/bin/env python3
"""
Pre-packages the complete modern Mozilla Root CA Certificate Store (`certifi`)
directly inside `app/src/main/assets/cacerts/` so `KoboArc7Freedom.apk` contains
ALL modern 2026 TLS Root CA certificates 100% OFFLINE out of the box.

Generates three artifacts inside `app/src/main/assets/cacerts/`:
  1. `android-hashed/<8-hex-old-hash>.<n>` — Exact Android 4.2 (`TrustedCertificateStore`
     / OpenSSL `X509_NAME_hash_old` MD5 canonical Subject hash) files ready to copy
     straight into `/system/etc/security/cacerts/` and `/data/misc/keychain/cacerts-added/`.
  2. `ca-bundle-2026.crt` — Complete concatenated PEM bundle for `libarc7tls.so`
     (modern TLS 1.2 + TLS 1.3 downloader & local browser gateway) and user export.
  3. `manifest.tsv` — Tab-separated index (`filename\tsubject\tnot_after`) displayed
     in the app UI and verified at build time.
"""
import os
import re
import shutil
import subprocess

import certifi

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "app/src/main/assets/cacerts")
HASHED_DIR = os.path.join(OUT_DIR, "android-hashed")

PEM_BLOCK_RE = re.compile(
    r"-----BEGIN CERTIFICATE-----\s+.*?\s+-----END CERTIFICATE-----",
    re.DOTALL,
)


def main() -> None:
    if os.path.exists(OUT_DIR):
        shutil.rmtree(OUT_DIR)
    os.makedirs(HASHED_DIR, exist_ok=True)

    bundle_src = certifi.where()
    with open(bundle_src, "r", encoding="utf-8") as f:
        raw_bundle = f.read()

    certs = PEM_BLOCK_RE.findall(raw_bundle)
    if len(certs) < 100:
        raise RuntimeError(f"Expected >= 100 root CAs in certifi bundle, found {len(certs)}")

    hash_counts: dict[str, int] = {}
    manifest_rows: list[str] = []
    clean_pems: list[str] = []

    for pem in certs:
        pem_norm = pem.strip() + "\n"
        proc = subprocess.run(
            [
                "openssl", "x509",
                "-subject_hash_old",
                "-subject",
                "-enddate",
                "-fingerprint", "-sha1",
                "-noout",
            ],
            input=pem_norm,
            capture_output=True,
            text=True,
            check=True,
        )
        lines = [ln.strip() for ln in proc.stdout.strip().splitlines() if ln.strip()]
        old_hash = lines[0].lower()
        assert re.fullmatch(r"[0-9a-f]{8}", old_hash), f"Unexpected subject_hash_old: {old_hash}"

        subject = ""
        not_after = ""
        for ln in lines[1:]:
            if ln.startswith("subject="):
                subject = ln[len("subject="):].strip()
            elif ln.startswith("notAfter="):
                not_after = ln[len("notAfter="):].strip()

        idx = hash_counts.get(old_hash, 0)
        hash_counts[old_hash] = idx + 1
        fname = f"{old_hash}.{idx}"

        # Format matching stock Android /system/etc/security/cacerts/<hash>.0 files:
        # PEM certificate header + X509 text metadata so Android Settings -> Security ->
        # Trusted Credentials displays every certificate cleanly in Android 4.2 UI!
        x509_text = subprocess.run(
            ["openssl", "x509", "-text", "-fingerprint", "-sha1"],
            input=pem_norm,
            capture_output=True,
            text=True,
            check=True,
        ).stdout

        with open(os.path.join(HASHED_DIR, fname), "w", encoding="utf-8") as out_f:
            out_f.write(x509_text)

        clean_pems.append(pem_norm)
        manifest_rows.append(f"{fname}\t{subject}\t{not_after}")

    bundle_out = os.path.join(OUT_DIR, "ca-bundle-2026.crt")
    with open(bundle_out, "w", encoding="utf-8") as bf:
        bf.write("".join(clean_pems))

    manifest_out = os.path.join(OUT_DIR, "manifest.tsv")
    with open(manifest_out, "w", encoding="utf-8") as mf:
        mf.write(f"# certifi={certifi.__version__} count={len(manifest_rows)}\n")
        for row in manifest_rows:
            mf.write(row + "\n")

    print(
        f"Packaged {len(manifest_rows)} pre-downloaded Root CA certificates "
        f"(certifi {certifi.__version__}) into {OUT_DIR}"
    )


if __name__ == "__main__":
    main()
