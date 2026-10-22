#!/usr/bin/env python3
"""
Patches the pre-bundled offline `FDroidClassicLegacy.apk` (`eu.bubu1.fdroidclassic.debug`,
minSdk=14 Android 4.0-4.2.2) so every built-in repository connects cleanly through
`KoboArc7Freedom`'s persistent local Mbed-TLS 3.6 `TLS 1.3` + 2026 Root CA gateway
(`http://127.0.0.1:8118/__https/...`), enables BOTH `F-Droid` AND `F-Droid Archive`
out of the box, 4-byte zipaligns `resources.arsc` (`ZIP_STORED`), and signs the APK with
`arc7freedom.jks` (`v1 + v2 + v3`) so Kobo Arc 7 auto-upgrades any older install cleanly.
"""
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

URL_REWRITES = {
    "https://ftp.fau.de/fdroid/repo/": "http://127.0.0.1:8118/__https/f-droid.org/repo",
    "https://ftp.fau.de/fdroid/repo": "http://127.0.0.1:8118/__https/f-droid.org/repo",
    "https://bubu1.eu/fdroidclassic/fdroid/repo/": "http://127.0.0.1:8118/__https/f-droid.org/repo",
    "https://bubu1.eu/fdroidclassic/fdroid/repo": "http://127.0.0.1:8118/__https/f-droid.org/repo",
    "https://f-droid.org/repo": "http://127.0.0.1:8118/__https/f-droid.org/repo",
    "https://f-droid.org/archive": "http://127.0.0.1:8118/__https/f-droid.org/archive",
    "https://guardianproject.info/fdroid/repo": "http://127.0.0.1:8118/__https/guardianproject.info/fdroid/repo",
    "https://guardianproject.info/fdroid/archive": "http://127.0.0.1:8118/__https/guardianproject.info/fdroid/archive",
    "https://archive.newpipe.net/fdroid/repo": "http://127.0.0.1:8118/__https/archive.newpipe.net/fdroid/repo",
    "https://briarproject.org/fdroid/repo": "http://127.0.0.1:8118/__https/briarproject.org/fdroid/repo",
}


def _read_utf8_len(buf: bytes, pos: int):
    b0 = buf[pos]
    pos += 1
    if b0 & 0x80:
        b1 = buf[pos]
        pos += 1
        return ((b0 & 0x7F) << 8) | b1, pos
    return b0, pos


def _encode_utf8_len(n: int) -> bytes:
    if n < 0x80:
        return bytes([n])
    return bytes([0x80 | ((n >> 8) & 0x7F), n & 0xFF])


def _read_utf16_len(buf: bytes, pos: int):
    u0 = struct.unpack_from("<H", buf, pos)[0]
    pos += 2
    if u0 & 0x8000:
        u1 = struct.unpack_from("<H", buf, pos)[0]
        pos += 2
        return ((u0 & 0x7FFF) << 16) | u1, pos
    return u0, pos


def _encode_utf16_len(n: int) -> bytes:
    if n < 0x8000:
        return struct.pack("<H", n)
    return struct.pack("<HH", 0x8000 | ((n >> 16) & 0x7FFF), n & 0xFFFF)


def patch_arsc_bytes(arsc: bytes):
    t_type, t_hdr_size, t_total_size, pkg_count = struct.unpack_from("<HHII", arsc, 0)
    assert t_type == 0x0002 and t_hdr_size == 12, f"Unexpected ARSC header: {t_type:#x}/{t_hdr_size}"

    sp_off = t_hdr_size
    (
        sp_type,
        sp_hdr_size,
        sp_size,
        str_count,
        style_count,
        flags,
        strings_start,
        styles_start,
    ) = struct.unpack_from("<HHIIIIII", arsc, sp_off)
    assert sp_type == 0x0001 and sp_hdr_size == 28, f"Unexpected StringPool header: {sp_type:#x}/{sp_hdr_size}"

    is_utf8 = bool(flags & (1 << 8))
    # Clear SORTED_FLAG (bit 0) since changed URL strings change lexicographic ordering slightly
    new_flags = flags & ~0x00000001

    offsets_pos = sp_off + sp_hdr_size
    str_offsets = struct.unpack_from(f"<{str_count}I", arsc, offsets_pos)
    style_offsets_raw = arsc[offsets_pos + 4 * str_count : offsets_pos + 4 * (str_count + style_count)]

    pool_data_abs = sp_off + strings_start
    strings = []
    for off in str_offsets:
        p = pool_data_abs + off
        if is_utf8:
            _, p = _read_utf8_len(arsc, p)
            byte_len, p = _read_utf8_len(arsc, p)
            raw_s = arsc[p : p + byte_len]
            strings.append(raw_s.decode("utf-8", errors="surrogateescape"))
        else:
            u16_len, p = _read_utf16_len(arsc, p)
            raw_s = arsc[p : p + u16_len * 2]
            strings.append(raw_s.decode("utf-16-le", errors="surrogatepass"))

    style_bytes = b""
    if style_count > 0 and styles_start > 0:
        style_bytes = arsc[sp_off + styles_start : sp_off + sp_size]

    rewritten_count = 0
    archive_url_idx = -1
    zero_str_idx = -1
    one_str_idx = -1

    for i, s in enumerate(strings):
        if s in URL_REWRITES:
            strings[i] = URL_REWRITES[s]
            rewritten_count += 1
        if strings[i] == "http://127.0.0.1:8118/__https/f-droid.org/archive":
            archive_url_idx = i
        elif strings[i] == "0" and zero_str_idx < 0:
            zero_str_idx = i
        elif strings[i] == "1" and one_str_idx < 0:
            one_str_idx = i

    # Re-encode global string pool preserving 100% identical string indices!
    new_offsets = []
    blob = bytearray()
    for s in strings:
        new_offsets.append(len(blob))
        if is_utf8:
            u16_len = len(s.encode("utf-16-le", errors="surrogatepass")) // 2
            b_utf8 = s.encode("utf-8", errors="surrogateescape")
            blob.extend(_encode_utf8_len(u16_len))
            blob.extend(_encode_utf8_len(len(b_utf8)))
            blob.extend(b_utf8)
            blob.append(0)
        else:
            b_u16 = s.encode("utf-16-le", errors="surrogatepass")
            blob.extend(_encode_utf16_len(len(b_u16) // 2))
            blob.extend(b_u16)
            blob.extend(b"\x00\x00")

    while len(blob) % 4 != 0:
        blob.append(0)

    new_strings_start = sp_hdr_size + 4 * (str_count + style_count)
    if style_count > 0 and len(style_bytes) > 0:
        new_styles_start = new_strings_start + len(blob)
        padded_styles = bytearray(style_bytes)
        while len(padded_styles) % 4 != 0:
            padded_styles.append(0)
    else:
        new_styles_start = 0
        padded_styles = bytearray()

    new_sp_size = new_strings_start + len(blob) + len(padded_styles)
    new_sp_hdr = struct.pack(
        "<HHIIIIII",
        sp_type,
        sp_hdr_size,
        new_sp_size,
        str_count,
        style_count,
        new_flags,
        new_strings_start,
        new_styles_start,
    )

    rest_mut = bytearray(arsc[sp_off + sp_size :])

    # Enable F-Droid Archive ("0" -> "1") in `array/default_repos` right next to `archive_url_idx`!
    # In `ResTable_map` (12 bytes per map item: uint32 name [4B] + Res_value(size=8, res0=0, dataType=0x03, data=strIdx) [8B]):
    # Item[1]'s Res_value starts at `idx`, so Item[4]'s Res_value ("inuse") starts at `idx + 3 * 12`!
    archive_enabled = False
    if archive_url_idx >= 0 and zero_str_idx >= 0 and one_str_idx >= 0:
        item1_pat = struct.pack("<HBBI", 8, 0, 0x03, archive_url_idx)
        pos = 0
        while True:
            idx = rest_mut.find(item1_pat, pos)
            if idx < 0:
                break
            item4_off = idx + 3 * 12
            if item4_off + 8 <= len(rest_mut):
                sz, r0, dt, val = struct.unpack_from("<HBBI", rest_mut, item4_off)
                if sz == 8 and dt == 0x03 and val in (zero_str_idx, one_str_idx):
                    struct.pack_into("<I", rest_mut, item4_off + 4, one_str_idx)
                    archive_enabled = True
            pos = idx + 8

    new_total_size = t_hdr_size + new_sp_size + len(rest_mut)
    new_table_hdr = struct.pack("<HHII", t_type, t_hdr_size, new_total_size, pkg_count)

    out = bytearray()
    out.extend(new_table_hdr)
    out.extend(new_sp_hdr)
    out.extend(struct.pack(f"<{str_count}I", *new_offsets))
    out.extend(style_offsets_raw)
    out.extend(blob)
    out.extend(padded_styles)
    out.extend(rest_mut)
    return bytes(out), rewritten_count, archive_enabled


def patch_fdroid_apk(apk_path: str, zipalign_bin: str, java_bin: str, apksigner_jar: str,
                     keystore_path: str, ks_pass: str, key_alias: str) -> None:
    with zipfile.ZipFile(apk_path, "r") as zf_in:
        arsc_orig = zf_in.read("resources.arsc")
        arsc_patched, rewritten_n, archive_on = patch_arsc_bytes(arsc_orig)

        with tempfile.TemporaryDirectory() as tmp:
            unsigned_apk = os.path.join(tmp, "unsigned.apk")
            aligned_apk = os.path.join(tmp, "aligned.apk")

            with zipfile.ZipFile(unsigned_apk, "w") as zf_out:
                for info in zf_in.infolist():
                    fn = info.filename
                    # Strip old META-INF entries so apksigner writes a spotless v1+v2+v3 JAR block
                    if fn.startswith("META-INF/"):
                        continue
                    new_info = zipfile.ZipInfo(filename=fn, date_time=info.date_time)
                    new_info.external_attr = info.external_attr
                    if fn == "resources.arsc":
                        new_info.compress_type = zipfile.ZIP_STORED
                        zf_out.writestr(new_info, arsc_patched)
                    else:
                        new_info.compress_type = info.compress_type
                        zf_out.writestr(new_info, zf_in.read(fn))

            subprocess.run([zipalign_bin, "-f", "-p", "4", unsigned_apk, aligned_apk], check=True)
            subprocess.run([
                java_bin, "-jar", apksigner_jar, "sign",
                "--ks", keystore_path,
                "--ks-key-alias", key_alias,
                "--ks-pass", f"pass:{ks_pass}",
                "--key-pass", f"pass:{ks_pass}",
                "--min-sdk-version", "14",
                "--v1-signing-enabled", "true",
                "--v2-signing-enabled", "true",
                "--v3-signing-enabled", "true",
                "--out", apk_path,
                aligned_apk,
            ], check=True)

    print(
        f"  Patched FDroidClassicLegacy.apk -> TLS 1.3 bridge active "
        f"(rewrote={rewritten_n}, archive_default_enabled={archive_on}, signed=v1+v2+v3)"
    )


if __name__ == "__main__":
    if len(sys.argv) != 8:
        sys.exit("Usage: patch-fdroid-apk.py <apk> <zipalign> <java> <apksigner.jar> <keystore.jks> <pass> <alias>")
    patch_fdroid_apk(*sys.argv[1:8])
