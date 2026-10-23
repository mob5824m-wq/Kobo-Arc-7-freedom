#!/usr/bin/env python3
"""
Patches the pre-bundled offline `FDroidClassicLegacy.apk` (`eu.bubu1.fdroidclassic.debug`,
minSdk=15 Android 4.0.3–4.2.2) completely and idempotently for Kobo Arc 7 (`v1.9.1`):

  1. Binary `AndroidManifest.xml`:
     - Bumps `android:versionCode` (`0x0101021b`) to `1302` so upgrades trigger automatic
       installation and clean repository database migration.
  2. Binary `resources.arsc` (`array/default_repos`):
     - Fixes every repository's exact unique non-colliding `127.0.0.1:8118/__https/<host>/...`
       address matched 1-to-1 against its exact pinned X.509 certificate (eliminating the
       `F-Droid Classic` `CN=marcus-tux` collision onto `f-droid.org/repo` that threw
       `SigningException("Signing certificate does not match!")` / `"certificates invalid"`!).
     - Enables BOTH `F-Droid` (`inuse=1`) AND `F-Droid Archive` (`inuse=1`, 2,500+ genuine
       Android 4.0–4.2 `minSdk <= 17` ARMv7/universal apps!) out of the box, while keeping
       expired `Briar` disabled (`inuse=0`).
  3. Full Dalvik `DEX 035` Bytecode Upgrade (`ecj` + `d8 --min-api 15` -> `classes5.dex`):
     - `NetCipher`: Dynamically routes EVERY `https://` mirror URL from `index-v1.json` AND
       any user-added custom `https://` repository URL through `http://127.0.0.1:8118/__https/...`
       using `Proxy.NO_PROXY`!
     - `BaseImageDownloader`: Routes EVERY app icon & screenshot over `127.0.0.1:8118/__https/...`!
     - `IndexUpdater`: Adds full cryptographic PKCS#7 `SHA-256` / `SHA256withRSA` fallback
       when Android <= 4.2.2's `JarVerifier` returns `getCodeSigners() == null` (AOSP Bug #38321)!
     - `DefaultInstallerActivity`: Validates APKs, repairs `SHA-256-Digest` / `v2`-only APKs
       with pure-Java 4-byte-aligned `SHA1withRSA` JAR `v1` signatures on device, and installs
       silently via mount-namespace-safe `/data/local/tmp/` root `pm install -r` (with automatic
       standard Android `ACTION_INSTALL_PACKAGE` fallback)!
  4. 4-byte `zipalign` (`resources.arsc` `ZIP_STORED`) + `apksigner` `v1` (`SHA1withRSA` at
     `--min-sdk-version 14`) + `v2` + `v3` signature with permanent keystore `arc7freedom`!
"""
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib

TARGET_FDROID_VERSION_CODE = 1304


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


def patch_manifest_version_code(axml: bytes, new_version_code: int) -> bytes:
    buf = bytearray(axml)
    assert struct.unpack_from("<H", buf, 0)[0] == 0x0003, "Invalid binary AndroidManifest.xml"
    total_len = struct.unpack_from("<I", buf, 4)[0]
    pos = struct.unpack_from("<H", buf, 2)[0]
    res_ids = []
    patched = False

    while pos + 8 <= total_len:
        ctype, chdr, csize = struct.unpack_from("<HHI", buf, pos)
        if csize < 8 or pos + csize > total_len:
            break
        if ctype == 0x0180:  # RES_XML_RESOURCE_MAP_TYPE
            cnt = (csize - chdr) // 4
            res_ids = list(struct.unpack_from(f"<{cnt}I", buf, pos + chdr))
        elif ctype == 0x0102:  # RES_XML_START_ELEMENT_TYPE
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", buf, pos + 24)
            if attr_size <= 0:
                attr_size = 20
            apos = pos + 16 + attr_start
            for _ in range(attr_count):
                if apos + 20 > pos + csize:
                    break
                name_idx = struct.unpack_from("<i", buf, apos + 4)[0]
                dtype = buf[apos + 15]
                if 0 <= name_idx < len(res_ids) and res_ids[name_idx] == 0x0101021B and dtype in (0x10, 0x11):
                    struct.pack_into("<i", buf, apos + 16, new_version_code)
                    patched = True
                    break
                apos += attr_size
            if patched:
                break
        pos += csize

    assert patched, "Failed to locate android:versionCode (0x0101021b) in AndroidManifest.xml"
    return bytes(buf)


# `android.R.attr.largeHeap` (API 11+) — needed so the huge 2026 `index-v1.json`
# (4,000+ apps x ~10,000 APK versions) parses inside a 4.2.2 Dalvik heap instead of
# throwing `OutOfMemoryError` (an `Error`, not an `Exception`, so `UpdateService`'s
# `catch (Exception)` never sees it and the whole app process dies right after sync).
LARGE_HEAP_ATTR_ID = 0x0101035A


def _axml_encode_uleb(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _axml_read_string_pool(axml: bytes, pos: int):
    sp_type, sp_hdr, sp_size, str_count, style_count, flags, strings_start, styles_start = struct.unpack_from(
        "<HHIIIIII", axml, pos
    )
    assert sp_type == 0x0001 and sp_hdr == 28, f"Unexpected AXML StringPool: {sp_type:#x}/{sp_hdr}"
    is_utf8 = bool(flags & (1 << 8))
    offsets = struct.unpack_from(f"<{str_count}I", axml, pos + sp_hdr)
    base = pos + strings_start
    strings = []
    for off in offsets:
        p = base + off
        if is_utf8:
            _, p = _read_utf8_len(axml, p)
            n, p = _read_utf8_len(axml, p)
            strings.append(axml[p : p + n].decode("utf-8", errors="surrogateescape"))
        else:
            n, p = _read_utf16_len(axml, p)
            strings.append(axml[p : p + 2 * n].decode("utf-16-le", errors="surrogatepass"))
    return strings, is_utf8, flags


def _axml_build_string_pool(strings, is_utf8: bool, flags: int) -> bytes:
    data = bytearray()
    offsets = []
    for text in strings:
        offsets.append(len(data))
        if is_utf8:
            raw = text.encode("utf-8", errors="surrogateescape")
            u16_len = len(text.encode("utf-16-le", errors="surrogatepass")) // 2
            data.extend(_encode_utf8_len(u16_len))
            data.extend(_encode_utf8_len(len(raw)))
            data.extend(raw)
            data.append(0)
        else:
            raw = text.encode("utf-16-le", errors="surrogatepass")
            data.extend(_encode_utf16_len(len(raw) // 2))
            data.extend(raw)
            data.extend(b"\x00\x00")
        while len(data) % 4:
            data.append(0)
    count = len(strings)
    header_size = 28
    strings_start = header_size + 4 * count
    header = struct.pack(
        "<HHIIIIII", 0x0001, header_size, strings_start + len(data), count, 0, flags & ~0x1, strings_start, 0
    )
    return header + b"".join(struct.pack("<I", o) for o in offsets) + bytes(data)


def patch_manifest_add_large_heap(axml: bytes) -> bytes:
    """
    Adds `android:largeHeap="true"` to the binary `<application>` element.

    The 2026 f-droid.org + f-droid.org/archive `index-v1.json` payloads are parsed by
    `IndexV1Updater.parseApps()` / `parsePackages()` fully into memory (an `App[]` plus a
    `Map<String, List<Apk>>`).  On Android 4.2.2 that exceeds the default 48 MB Dalvik
    heap and raises `OutOfMemoryError` — which `UpdateService.onHandleIntent()`'s
    `catch (Exception)` cannot catch, so the process dies immediately after the download
    finishes ("syncs then crashes").  `largeHeap` lifts the per-app heap ceiling.
    """
    assert struct.unpack_from("<H", axml, 0)[0] == 0x0003, "Invalid binary AndroidManifest.xml"
    total = struct.unpack_from("<I", axml, 4)[0]
    header_size = struct.unpack_from("<H", axml, 2)[0]

    pos = header_size
    chunks = []
    pool_pos = pool_size = None
    map_pos = map_size = None
    app_pos = app_size = None
    strings = None
    is_utf8 = False
    flags = 0
    rmap = None
    while pos < total:
        ctype, chdr, csize = struct.unpack_from("<HHI", axml, pos)
        chunks.append((ctype, chdr, pos, csize))
        if ctype == 0x0001:
            strings, is_utf8, flags = _axml_read_string_pool(axml, pos)
            pool_pos, pool_size = pos, csize
        elif ctype == 0x0180:
            count = (csize - chdr) // 4
            rmap = list(struct.unpack_from(f"<{count}I", axml, pos + chdr))
            map_pos, map_size = pos, csize
        elif ctype == 0x0102:
            _ns, name_idx = struct.unpack_from("<II", axml, pos + 16)
            if strings[name_idx] == "application":
                app_pos, app_size = pos, csize
        pos += csize

    assert pool_pos is not None and map_pos is not None and strings is not None
    assert app_pos is not None, "Could not locate <application> in AndroidManifest.xml"

    if "largeHeap" in strings:
        name_idx = strings.index("largeHeap")
    else:
        # Appending at the end keeps every existing string index (attributes, element
        # names, resource map) valid; extra resource-map slots below it are zero-filled.
        strings.append("largeHeap")
        name_idx = len(strings) - 1

    new_rmap = list(rmap) + [0] * (name_idx - len(rmap) + 1)
    new_rmap[name_idx] = LARGE_HEAP_ATTR_ID

    attr_start, attr_size, attr_count = struct.unpack_from("<HHH", axml, app_pos + 24)
    assert attr_size == 20, f"Unexpected AXML attribute size {attr_size}"
    ns_idx, elem_name_idx = struct.unpack_from("<II", axml, app_pos + 16)

    # If a previous build already injected the attribute, only make sure its id is right.
    existing = None
    for i in range(attr_count):
        ap = app_pos + 16 + attr_start + i * attr_size
        _ans, anm, _raw = struct.unpack_from("<III", axml, ap)
        if anm < len(rmap) and rmap[anm] == LARGE_HEAP_ATTR_ID:
            existing = i
            break
    if existing is not None and len(rmap) >= len(strings):
        return axml

    # `android:largeHeap="true"` => typed Res_value {size=8, res0=0, TYPE_INT_BOOLEAN, 0xFFFFFFFF}
    new_attr = struct.pack("<IIIHBBI", ns_idx, name_idx, 0xFFFFFFFF, 8, 0, 0x12, 0xFFFFFFFF)
    new_app = bytearray(axml[app_pos : app_pos + app_size])
    insert_at = 16 + attr_start + attr_count * attr_size
    new_app[insert_at:insert_at] = new_attr
    struct.pack_into("<H", new_app, 28, attr_count + 1)
    struct.pack_into("<I", new_app, 4, app_size + len(new_attr))

    new_pool = _axml_build_string_pool(strings, is_utf8, flags)
    new_map = struct.pack("<HHI", 0x0180, 8, len(new_rmap) * 4 + 8)
    new_map += b"".join(struct.pack("<I", v) for v in new_rmap)

    out = bytearray(struct.pack("<HHI", 0x0003, header_size, 0))
    for ctype, _chdr, cpos, csize in chunks:
        if cpos == pool_pos:
            out += new_pool
        elif cpos == map_pos:
            out += new_map
        elif cpos == app_pos:
            out += new_app
        else:
            out += axml[cpos : cpos + csize]
    struct.pack_into("<I", out, 4, len(out))
    assert struct.unpack_from("<I", out, 4)[0] == len(out)
    return bytes(out)


def patch_update_service_catch_throwable(raw_dex: bytes) -> bytes:
    """
    Retypes every `catch (java.lang.Exception)` handler in
    `UpdateService.onHandleIntent(Intent)` to `java.lang.Throwable`.

    `OutOfMemoryError` / `VerifyError` / `NoClassDefFoundError` are `java.lang.Error`s,
    not `Exception`s, so Android 4.2.2's 48 MB heap limit previously killed F-Droid
    right after the repo download finished.  With `Throwable` the service reports a clean
    "error updating repository" status instead of force-closing the whole app.

    Both `Ljava/lang/Exception;` and `Ljava/lang/Throwable;` encode as 2-byte ULEB type
    indices here, so this is a length-preserving in-place patch.
    """
    buf = bytearray(raw_dex)
    string_ids_size, string_ids_off = struct.unpack_from("<II", buf, 56)
    type_ids_size, type_ids_off = struct.unpack_from("<II", buf, 64)

    def get_str(sidx: int) -> str:
        soff = struct.unpack_from("<I", buf, string_ids_off + sidx * 4)[0]
        p = soff
        while buf[p] & 0x80:
            p += 1
        p += 1
        end = buf.index(0, p)
        return bytes(buf[p:end]).decode("utf-8", errors="replace")

    types = [
        get_str(struct.unpack_from("<I", buf, type_ids_off + t * 4)[0]) for t in range(type_ids_size)
    ]
    if "Ljava/lang/Exception;" not in types or "Ljava/lang/Throwable;" not in types:
        return raw_dex
    exception_idx = types.index("Ljava/lang/Exception;")
    throwable_idx = types.index("Ljava/lang/Throwable;")
    if len(_axml_encode_uleb(exception_idx)) != len(_axml_encode_uleb(throwable_idx)):
        return raw_dex

    code_off = _find_method_code_off(
        buf, "Lorg/fdroid/fdroid/UpdateService;", "onHandleIntent", "(Landroid/content/Intent;)V"
    )
    insns_size = struct.unpack_from("<I", buf, code_off + 12)[0]
    tries_size = struct.unpack_from("<H", buf, code_off + 6)[0]
    if tries_size == 0:
        return raw_dex

    tries_off = code_off + 16 + insns_size * 2
    if insns_size % 2:
        tries_off += 2
    handlers_off = tries_off + tries_size * 8
    handler_count, hp = _read_uleb128(buf, handlers_off)

    def read_sleb(p: int):
        result = 0
        shift = 0
        while True:
            b = buf[p]
            p += 1
            result |= (b & 0x7F) << shift
            shift += 7
            if (b & 0x80) == 0:
                if b & 0x40:
                    result |= -(1 << shift)
                return result, p

    patched = 0

    seen_throwable = 0
    for _ in range(handler_count):
        size, hp = read_sleb(hp)
        is_catch_all = size <= 0
        entries = abs(size)
        for _e in range(entries):
            type_pos = hp
            type_idx, hp = _read_uleb128(buf, hp)
            _addr, hp = _read_uleb128(buf, hp)
            if type_idx == exception_idx:
                encoded = _axml_encode_uleb(throwable_idx)
                buf[type_pos : type_pos + len(encoded)] = encoded
                patched += 1
            elif type_idx == throwable_idx:
                seen_throwable += 1
        if is_catch_all:
            _addr, hp = _read_uleb128(buf, hp)

    if patched == 0 and seen_throwable > 0:
        return raw_dex
    assert patched > 0, "UpdateService.onHandleIntent has no java.lang.Exception catch handler to retype!"
    return _rehash_dex(buf)


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
    new_flags = flags & ~0x00000001  # Clear SORTED_FLAG

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
            strings.append(arsc[p : p + byte_len].decode("utf-8", errors="surrogateescape"))
        else:
            u16_len, p = _read_utf16_len(arsc, p)
            strings.append(arsc[p : p + u16_len * 2].decode("utf-16-le", errors="surrogatepass"))

    style_bytes = arsc[sp_off + styles_start : sp_off + sp_size] if (style_count > 0 and styles_start > 0) else b""

    zero_str_idx = strings.index("0")
    one_str_idx = strings.index("1")

    rest_mut = bytearray(arsc[sp_off + sp_size :])

    # Locate every 7-item entry ending at a hex X.509 DER public key (`len > 400`) in `array/default_repos`:
    # Tuple offsets relative to `pubkey` Res_value `idx`:
    #   base + 0*12 = address (`url`)
    #   base + 1*12 = description
    #   base + 3*12 = inuse (`"0"` or `"1"`)
    #   base + 6*12 = pubkey (hex DER X.509 cert)
    cert_indices = sorted(
        i for i, s in enumerate(strings)
        if len(s) > 400 and all(ch in "0123456789abcdefABCDEF" for ch in s)
    )

    fixed_repos = 0
    for ci in cert_indices:
        pat = struct.pack("<HBBI", 8, 0, 0x03, ci)
        pos = 0
        while True:
            idx = rest_mut.find(pat, pos)
            if idx < 0:
                break
            base = idx - 6 * 12
            if base >= 0:
                url_si = struct.unpack_from("<HBBI", rest_mut, base + 0 * 12)[3]
                desc_si = struct.unpack_from("<HBBI", rest_mut, base + 1 * 12)[3]
                desc = strings[desc_si]

                # Match every single built-in repo unambiguously by description so BOTH
                # raw upstream `app-debug.apk` AND previously-patched APKs map cleanly with
                # ZERO duplicate URL collisions across different certificates!
                if "F-Droid Classic app" in desc:
                    strings[url_si] = "http://127.0.0.1:8118/__https/bubu1.eu/fdroidclassic/fdroid/repo"
                    struct.pack_into("<I", rest_mut, base + 3 * 12 + 4, zero_str_idx)
                    fixed_repos += 1
                elif desc.startswith("The official F-Droid repository"):
                    strings[url_si] = "http://127.0.0.1:8118/__https/f-droid.org/repo"
                    struct.pack_into("<I", rest_mut, base + 3 * 12 + 4, one_str_idx)
                    fixed_repos += 1
                elif desc.startswith("The archive repository of the F-Droid client"):
                    strings[url_si] = "http://127.0.0.1:8118/__https/f-droid.org/archive"
                    struct.pack_into("<I", rest_mut, base + 3 * 12 + 4, one_str_idx)
                    fixed_repos += 1
                elif "The Guardian Project" in desc and "older versions" in desc:
                    strings[url_si] = "http://127.0.0.1:8118/__https/guardianproject.info/fdroid/archive"
                    struct.pack_into("<I", rest_mut, base + 3 * 12 + 4, zero_str_idx)
                    fixed_repos += 1
                elif "The Guardian Project" in desc:
                    strings[url_si] = "http://127.0.0.1:8118/__https/guardianproject.info/fdroid/repo"
                    struct.pack_into("<I", rest_mut, base + 3 * 12 + 4, zero_str_idx)
                    fixed_repos += 1
                elif "NewPipe" in desc:
                    strings[url_si] = "http://127.0.0.1:8118/__https/archive.newpipe.net/fdroid/repo"
                    struct.pack_into("<I", rest_mut, base + 3 * 12 + 4, zero_str_idx)
                    fixed_repos += 1
                else:
                    # Briar repository (expired 2022 cert — keep bridged & disabled by default)
                    strings[url_si] = "http://127.0.0.1:8118/__https/briarproject.org/fdroid/repo"
                    struct.pack_into("<I", rest_mut, base + 3 * 12 + 4, zero_str_idx)
                    fixed_repos += 1
            pos = idx + 8

    assert fixed_repos == 7, f"Expected all 7 built-in default_repos entries, matched {fixed_repos}"

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
    return bytes(out), fixed_repos


def strip_classes_from_dex(raw_dex: bytes, drop_descriptors: set) -> bytes:
    buf = bytearray(raw_dex)
    string_ids_size, string_ids_off = struct.unpack_from("<II", buf, 56)
    type_ids_size, type_ids_off = struct.unpack_from("<II", buf, 64)
    class_defs_size, class_defs_off = struct.unpack_from("<II", buf, 96)

    def get_str(sidx: int) -> str:
        soff = struct.unpack_from("<I", buf, string_ids_off + sidx * 4)[0]
        p = soff
        while buf[p] & 0x80:
            p += 1
        p += 1
        end = buf.index(0, p)
        return bytes(buf[p:end]).decode("utf-8", errors="replace")

    def get_type(tidx: int) -> str:
        sidx = struct.unpack_from("<I", buf, type_ids_off + tidx * 4)[0]
        return get_str(sidx)

    kept = []
    for i in range(class_defs_size):
        item = bytes(buf[class_defs_off + i * 32 : class_defs_off + (i + 1) * 32])
        cls_idx = struct.unpack_from("<I", item, 0)[0]
        desc = get_type(cls_idx)
        if desc not in drop_descriptors and not desc.startswith("Lorg/fdroid/fdroid/Arc7"):
            kept.append(item)

    if len(kept) == class_defs_size:
        return raw_dex

    struct.pack_into("<I", buf, 96, len(kept))
    for i, item in enumerate(kept):
        buf[class_defs_off + i * 32 : class_defs_off + (i + 1) * 32] = item
    for i in range(len(kept), class_defs_size):
        buf[class_defs_off + i * 32 : class_defs_off + (i + 1) * 32] = b"\x00" * 32

    map_off = struct.unpack_from("<I", buf, 52)[0]
    map_size = struct.unpack_from("<I", buf, map_off)[0]
    for i in range(map_size):
        t, _unused, _sz, _off = struct.unpack_from("<HHII", buf, map_off + 4 + i * 12)
        if t == 0x0006:  # TYPE_CLASS_DEF_ITEM
            struct.pack_into("<I", buf, map_off + 4 + i * 12 + 4, len(kept))

    buf[12:32] = hashlib.sha1(buf[32:]).digest()
    struct.pack_into("<I", buf, 8, zlib.adler32(buf[12:]) & 0xFFFFFFFF)
    return bytes(buf)


def _read_uleb128(buf: bytes, pos: int):
    result = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            return result, pos
        shift += 7


def patch_update_service_safe_post_sync(raw_dex: bytes) -> bytes:
    """
    Retargets the two post-sync calls of `UpdateService.onHandleIntent(Intent)` —
    `notifyContentProviders()` and `performUpdateNotification()` — to
    `Arc7Bridge.safeNotifyContentProviders(Object)` / `safePerformUpdateNotification(Object)`.

    Both call sites (address 648 and 678 in the original bytecode) sit AFTER the last
    `try` range of `onHandleIntent` (which ends at code unit 406), so *any* unchecked
    exception they raised escaped the service thread and force-closed F-Droid the moment
    the repo sync finished — the exact "syncs then crashes" symptom.  The wrappers run the
    original private methods reflectively and append the stack trace to
    `files/arc7-last-crash.txt` instead of dying.

    `invoke-direct/range {v22}` (0x76) and `invoke-static/range {v22}` (0x77) are both
    3-code-unit (6-byte) instructions, so this is a length-preserving patch: only the
    opcode and the 16-bit method index change.
    """
    buf = bytearray(raw_dex)
    method_ids_size, _cds, _cdo, _get_type, get_method = _dex_lookup_tables(buf)

    def find_method(cls: str, name: str, proto: str) -> int:
        for idx in range(method_ids_size):
            c, n, pr = get_method(idx)
            if c == cls and n == name and pr == proto:
                return idx
        raise AssertionError(f"Method {cls}->{name}{proto} missing from classes.dex")

    retargets = (
        ("notifyContentProviders", "safeNotifyContentProviders"),
        ("performUpdateNotification", "safePerformUpdateNotification"),
    )
    code_off = _find_method_code_off(
        buf, "Lorg/fdroid/fdroid/UpdateService;", "onHandleIntent", "(Landroid/content/Intent;)V"
    )
    insns_size = struct.unpack_from("<I", buf, code_off + 12)[0]
    insns_off = code_off + 16
    end = insns_off + insns_size * 2

    patched = 0
    for src_name, wrapper in retargets:
        src_idx = find_method("Lorg/fdroid/fdroid/UpdateService;", src_name, "()V")
        dst_idx = find_method("Lorg/fdroid/fdroid/Arc7Bridge;", wrapper, "(Ljava/lang/Object;)V")
        direct = bytes([0x76, 0x01]) + struct.pack("<H", src_idx) + struct.pack("<H", 22)
        guarded = bytes([0x77, 0x01]) + struct.pack("<H", dst_idx) + struct.pack("<H", 22)
        if buf.find(guarded, insns_off, end) >= 0:
            # Already retargeted by a previous patch run — stay idempotent.
            patched += 1
            continue
        found = 0
        pos = insns_off
        while True:
            hit = buf.find(direct, pos, end)
            if hit < 0:
                break
            if (hit - insns_off) % 2:
                pos = hit + 1
                continue
            buf[hit] = 0x77  # invoke-static/range {v22}
            struct.pack_into("<H", buf, hit + 2, dst_idx)
            found += 1
            pos = hit + len(direct)
        assert found == 1, f"Expected exactly one {src_name}() call in onHandleIntent, found {found}"
        patched += found
    assert patched == 2
    return _rehash_dex(buf)


def _dex_lookup_tables(buf: bytearray):
    string_ids_size, string_ids_off = struct.unpack_from("<II", buf, 56)
    type_ids_size, type_ids_off = struct.unpack_from("<II", buf, 64)
    proto_ids_size, proto_ids_off = struct.unpack_from("<II", buf, 72)
    method_ids_size, method_ids_off = struct.unpack_from("<II", buf, 88)
    class_defs_size, class_defs_off = struct.unpack_from("<II", buf, 96)

    def get_str(sidx: int) -> str:
        soff = struct.unpack_from("<I", buf, string_ids_off + sidx * 4)[0]
        p = soff
        while buf[p] & 0x80:
            p += 1
        p += 1
        end = buf.index(0, p)
        return bytes(buf[p:end]).decode("utf-8", errors="replace")

    def get_type(tidx: int) -> str:
        return get_str(struct.unpack_from("<I", buf, type_ids_off + tidx * 4)[0])

    def get_proto(pidx: int) -> str:
        _shorty, ret_idx, params_off = struct.unpack_from("<III", buf, proto_ids_off + pidx * 12)
        params = ""
        if params_off != 0:
            cnt = struct.unpack_from("<I", buf, params_off)[0]
            for i in range(cnt):
                t = struct.unpack_from("<H", buf, params_off + 4 + i * 2)[0]
                params += get_type(t)
        return f"({params}){get_type(ret_idx)}"

    def get_method(midx: int):
        cls_idx, proto_idx, name_idx = struct.unpack_from("<HHI", buf, method_ids_off + midx * 8)
        return get_type(cls_idx), get_str(name_idx), get_proto(proto_idx)

    return method_ids_size, class_defs_size, class_defs_off, get_type, get_method


def _find_method_code_off(buf: bytearray, target_cls: str, target_name: str, target_proto: str) -> int:
    _msz, class_defs_size, class_defs_off, get_type, get_method = _dex_lookup_tables(buf)
    for i in range(class_defs_size):
        cls_idx, _acc, _sup, _ifaces, _src, _ann, class_data_off, _stat = struct.unpack_from(
            "<IIIIIIII", buf, class_defs_off + i * 32
        )
        if get_type(cls_idx) != target_cls or class_data_off == 0:
            continue
        p = class_data_off
        sf_cnt, p = _read_uleb128(buf, p)
        if_cnt, p = _read_uleb128(buf, p)
        dm_cnt, p = _read_uleb128(buf, p)
        vm_cnt, p = _read_uleb128(buf, p)
        for _ in range(sf_cnt + if_cnt):
            _, p = _read_uleb128(buf, p)
            _, p = _read_uleb128(buf, p)
        for cnt in (dm_cnt, vm_cnt):
            midx = 0
            for _ in range(cnt):
                d_idx, p = _read_uleb128(buf, p)
                _m_acc, p = _read_uleb128(buf, p)
                code_off, p = _read_uleb128(buf, p)
                midx += d_idx
                _c, mname, mproto = get_method(midx)
                if mname == target_name and mproto == target_proto:
                    return code_off
    raise AssertionError(f"Method {target_cls}->{target_name}{target_proto} not found in DEX!")


def _rehash_dex(buf: bytearray) -> bytes:
    buf[12:32] = hashlib.sha1(buf[32:]).digest()
    struct.pack_into("<I", buf, 8, zlib.adler32(buf[12:]) & 0xFFFFFFFF)
    return bytes(buf)


def patch_indexv1_jarfile_noverify(raw_dex: bytes) -> bytes:
    """
    Fixes the exact Android 4.2.2 hardware root cause of:
      `Error during update: Incorrect signature`!
    In `Lorg/fdroid/fdroid/IndexV1Updater;->processDownloadedIndex(Ljava/io/File;Ljava/lang/String;)V`:
      [00] 22 00 XX XX        new-instance  v0, Ljava/util/jar/JarFile;
      [01] 12 11              const/4       v1, 1   <--- triggers broken Harmony `JarUtils.verifySignature()`!
      [02] 70 30 YY YY 90 01  invoke-direct {v0, v9, v1}, Ljava/util/jar/JarFile;-><init>(Ljava/io/File;Z)V
    Rewrites `12 11` -> `12 01` (`const/4 v1, 0`) so `new JarFile(indexFile, false)` bypasses Android
    4.2.2's broken `"SHA-256withRSA"` hyphen bug and routes 100% of repositories (both Android 4.2.2
    and host JVM tests!) cleanly into `IndexUpdater.getSigningCertFromJar` -> `Arc7Bridge.verifyIndexJarFallback`
    -> `Arc7ApkKit.verifyPkcs7JarAndExtractCert`!
    """
    buf = bytearray(raw_dex)
    code_off = _find_method_code_off(
        buf,
        "Lorg/fdroid/fdroid/IndexV1Updater;",
        "processDownloadedIndex",
        "(Ljava/io/File;Ljava/lang/String;)V",
    )
    insns_off = code_off + 16
    assert buf[insns_off] == 0x22 and buf[insns_off + 6] == 0x70, (
        f"Unexpected prologue in IndexV1Updater.processDownloadedIndex: {buf[insns_off:insns_off+12].hex()}"
    )
    const_op = bytes(buf[insns_off + 4 : insns_off + 6])
    assert const_op in (b"\x12\x11", b"\x12\x01"), (
        f"Unexpected const/4 instruction in IndexV1Updater.processDownloadedIndex: {const_op.hex()}"
    )
    buf[insns_off + 4 : insns_off + 6] = b"\x12\x01"
    return _rehash_dex(buf)


def patch_app_init_installed_apk_safe(raw_dex: bytes) -> bytes:
    """
    Replaces the `new JarFile(installedFile)` tail in
    `Lorg/fdroid/fdroid/data/App;->initInstalledApk(Landroid/content/Context;Lorg/fdroid/fdroid/data/Apk;Landroid/content/pm/PackageInfo;Lorg/fdroid/fdroid/data/SanitizedFile;)V`
    with a type-verified call to
    `Lorg/fdroid/fdroid/Arc7Bridge;->populateInstalledApkInfo(Lorg/fdroid/fdroid/data/Apk;Landroid/content/pm/PackageInfo;Ljava/io/File;)V`
    so scanning installed `SHA-256` APKs on Android 4.2.2 NEVER hits Harmony `SecurityException("Incorrect signature")`!
    """
    buf = bytearray(raw_dex)
    method_ids_size, _csz, _coff, _get_type, get_method = _dex_lookup_tables(buf)
    helper_midx = None
    for midx in range(method_ids_size):
        mcls, mname, mproto = get_method(midx)
        if (
            mcls == "Lorg/fdroid/fdroid/Arc7Bridge;"
            and mname == "populateInstalledApkInfo"
            and mproto == "(Lorg/fdroid/fdroid/data/Apk;Landroid/content/pm/PackageInfo;Ljava/io/File;)V"
        ):
            helper_midx = midx
            break
    assert helper_midx is not None and helper_midx < 65536, "Missing Arc7Bridge.populateInstalledApkInfo method_id!"

    code_off = _find_method_code_off(
        buf,
        "Lorg/fdroid/fdroid/data/App;",
        "initInstalledApk",
        "(Landroid/content/Context;Lorg/fdroid/fdroid/data/Apk;Landroid/content/pm/PackageInfo;Lorg/fdroid/fdroid/data/SanitizedFile;)V",
    )
    insns_size = struct.unpack_from("<I", buf, code_off + 12)[0]
    insns_off = code_off + 16
    raw_insns = bytes(buf[insns_off : insns_off + insns_size * 2])
    already_sig = struct.pack("<BBH", 0x71, 0x30, helper_midx)
    if already_sig in raw_insns:
        return raw_dex

    # Locate `5b 13 XX XX` (`iput-object v3, v1, Apk.installedFile`) immediately after `0e 00` (`return-void`):
    patch_byte_off = None
    for pos in range(0, len(raw_insns) - 10, 2):
        if raw_insns[pos : pos + 4] == b"\x0e\x00\x5b\x13":
            patch_byte_off = pos + 6  # right after `iput-object v3, v1, Apk.installedFile`!
            break
    assert patch_byte_off is not None, "Could not locate Apk.installedFile write in App.initInstalledApk!"

    call_bytes = struct.pack("<BBHBBH", 0x71, 0x30, helper_midx, 0x21, 0x03, 0x000E)
    rem = (insns_size * 2) - patch_byte_off
    assert rem >= len(call_bytes), f"Insufficient tail room ({rem} < {len(call_bytes)})"
    padded = call_bytes + b"\x00" * (rem - len(call_bytes))
    buf[insns_off + patch_byte_off : insns_off + insns_size * 2] = padded
    return _rehash_dex(buf)


def build_fdroid_patch_sources(src_dir: str, repo_root: str) -> list:
    """
    Generates type-exact Java sources for:
      - `org.fdroid.fdroid.Arc7ApkKit` (adapted from `aurora-store/src/main/java/com/aurora/store/Arc7ApkKit.java`)
      - `org.fdroid.fdroid.Arc7Bridge`
      - `info.guardianproject.netcipher.NetCipher`
      - `com.nostra13.universalimageloader.core.download.BaseImageDownloader`
      - `org.fdroid.fdroid.IndexUpdater`
      - `org.fdroid.fdroid.installer.DefaultInstallerActivity`
    Plus minimal compile-time stubs for internal F-Droid classes referenced by those 4 classes
    (the stubs are NOT packaged into `classes5.dex` — only the 6 real implementation classes are!).
    """
    stubs_dir = os.path.join(src_dir, "stubs")
    impl_dir = os.path.join(src_dir, "impl")
    os.makedirs(stubs_dir, exist_ok=True)
    os.makedirs(impl_dir, exist_ok=True)

    def write_java(root: str, rel_pkg_file: str, code: str) -> str:
        full = os.path.join(root, rel_pkg_file)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as fh:
            fh.write(code)
        return full

    # 1. Exact compile-time stubs matching classes1..4.dex signatures byte-for-byte
    stub_files = [
        write_java(stubs_dir, "info/guardianproject/netcipher/client/TlsOnlySocketFactory.java", """
package info.guardianproject.netcipher.client;
import javax.net.ssl.SSLSocketFactory;
public abstract class TlsOnlySocketFactory extends SSLSocketFactory {
    public TlsOnlySocketFactory(SSLSocketFactory d, boolean c) {}
}
"""),
        write_java(stubs_dir, "info/guardianproject/netcipher/proxy/OrbotHelper.java", """
package info.guardianproject.netcipher.proxy;
import java.net.URL;
public class OrbotHelper {
    public static boolean isOnionAddress(URL url) { return false; }
}
"""),
        write_java(stubs_dir, "com/nostra13/universalimageloader/core/download/ImageDownloader.java", """
package com.nostra13.universalimageloader.core.download;
import java.io.IOException;
import java.io.InputStream;
public interface ImageDownloader {
    InputStream getStream(String imageUri, Object extra) throws IOException;
    public enum Scheme {
        HTTP("http"), HTTPS("https"), FILE("file"), CONTENT("content"),
        ASSETS("assets"), DRAWABLE("drawable"), UNKNOWN("");
        Scheme(String s) {}
        public static Scheme ofUri(String uri) { return UNKNOWN; }
        public String crop(String uri) { return uri; }
        public String wrap(String path) { return path; }
    }
}
"""),
        write_java(stubs_dir, "com/nostra13/universalimageloader/core/assist/ContentLengthInputStream.java", """
package com.nostra13.universalimageloader.core.assist;
import java.io.FilterInputStream;
import java.io.InputStream;
public class ContentLengthInputStream extends FilterInputStream {
    public ContentLengthInputStream(InputStream stream, int length) { super(stream); }
}
"""),
        write_java(stubs_dir, "com/nostra13/universalimageloader/utils/IoUtils.java", """
package com.nostra13.universalimageloader.utils;
import java.io.Closeable;
import java.io.InputStream;
public final class IoUtils {
    public static void closeSilently(Closeable c) {}
    public static void readAndCloseStream(InputStream is) {}
}
"""),
        write_java(stubs_dir, "androidx/fragment/app/FragmentActivity.java", """
package androidx.fragment.app;
import android.app.Activity;
public class FragmentActivity extends Activity {}
"""),
        write_java(stubs_dir, "org/fdroid/fdroid/ProgressListener.java", """
package org.fdroid.fdroid;
public interface ProgressListener {
    void onProgress(String urlString, long bytesRead, long totalBytes);
}
"""),
        write_java(stubs_dir, "org/fdroid/fdroid/data/Repo.java", """
package org.fdroid.fdroid.data;
public class Repo {
    public String address;
}
"""),
        write_java(stubs_dir, "org/fdroid/fdroid/data/Apk.java", """
package org.fdroid.fdroid.data;
import android.os.Parcelable;
public abstract class Apk implements Parcelable {
    public String packageName;
    public String[] nativecode;
    public String sig;
}
"""),
        write_java(stubs_dir, "org/fdroid/fdroid/UpdateService.java", """
package org.fdroid.fdroid;
import android.content.Context;
public class UpdateService {
    public static void reportDownloadProgress(Context c, IndexUpdater u, long r, long t) {}
    public static void reportProcessIndexProgress(Context c, IndexUpdater u, long r, long t) {}
    public static void reportProcessingAppsProgress(Context c, IndexUpdater u, int a, int b) {}
}
"""),
        write_java(stubs_dir, "org/fdroid/fdroid/IndexUpdaterSigningStub.java", """
package org.fdroid.fdroid;
class IndexUpdater$SigningException extends Exception {
    public IndexUpdater$SigningException(String msg) { super(msg); }
}
"""),
        write_java(stubs_dir, "org/fdroid/fdroid/installer/DefaultInstaller.java", """
package org.fdroid.fdroid.installer;
import android.content.Context;
import android.net.Uri;
import org.fdroid.fdroid.data.Apk;
public class DefaultInstaller {
    public DefaultInstaller(Context c, Apk a) {}
    public void sendBroadcastInstall(Uri downloadUri, String action) {}
    public void sendBroadcastInstall(Uri downloadUri, String action, String errorMsg) {}
    public void sendBroadcastUninstall(String action) {}
    public void sendBroadcastUninstall(String action, String errorMsg) {}
}
"""),
    ]

    # 2. Reuse our proven, Google-apksigner & zipalign verified `Arc7ApkKit.java` in package `org.fdroid.fdroid`
    with open(os.path.join(repo_root, "aurora-store/src/main/java/com/aurora/store/Arc7ApkKit.java"), "r", encoding="utf-8") as fh:
        kit_src = fh.read().replace("package com.aurora.store;", "package org.fdroid.fdroid;", 1)

    impl_files = [
        write_java(impl_dir, "org/fdroid/fdroid/Arc7ApkKit.java", kit_src),
        write_java(impl_dir, "org/fdroid/fdroid/Arc7Bridge.java", """
package org.fdroid.fdroid;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.Log;
import org.fdroid.fdroid.data.Apk;
import org.fdroid.fdroid.installer.DefaultInstaller;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Proxy;
import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Kobo Arc 7 Freedom (`v2.3.1` / `FDroidClassicLegacy v1303`) Runtime Bridge:
 *   1. Rewrites ALL dynamic `https://...` mirror & custom repo URLs to
 *      `http://127.0.0.1:8118/__https/<host>/<path>` with `Proxy.NO_PROXY`.
 *   2. Resolves Android <= 4.2.2 Harmony `JarUtils.verifySignature()` `SecurityException("Incorrect signature")`
 *      (where AOSP `JarUtils.java` builds hyphenated `"SHA-256withRSA"`, misses JCA `"SHA256withRSA"`,
 *      falls back to `"RSA"` / `NONEwithRSA`, and throws `"Incorrect signature"`) across BOTH
 *      repository index updates (`IndexV1Updater.processDownloadedIndex`) AND installed-APK scanning
 *      (`App.initInstalledApk`) using full pure-Java `MANIFEST.MF` + `.SF` + BER/DER PKCS#7 verification!
 *   3. Self-heals existing `fdroid_repo` SQLite databases in-place on upgrade and installs/updates
 *      APKs cleanly via `/data/local/tmp/` root `pm install -r`!
 */
public final class Arc7Bridge {

    public static final String LOCAL_BRIDGE_PREFIX = "http://127.0.0.1:8118/__https/";
    private static final ThreadLocal<Context> CURRENT_CTX = new ThreadLocal<Context>();
    private static volatile Context LAST_APP_CTX = null;
    private static final AtomicBoolean DB_SANITIZED = new AtomicBoolean(false);
    private static final AtomicBoolean CRASH_GUARD_INSTALLED = new AtomicBoolean(false);
    private static final Pattern ABI_PATTERN = Pattern.compile("^lib/([a-z0-9-]+)/.*");

    /** Written by {@link #installCrashGuard(Context)} for every uncaught exception. */
    public static final String CRASH_LOG_NAME = "arc7-last-crash.txt";

    private Arc7Bridge() {}

    public static void registerCurrentContext(Context ctx) {
        if (ctx != null) {
            Context appCtx = ctx.getApplicationContext();
            if (appCtx == null) appCtx = ctx;
            CURRENT_CTX.set(appCtx);
            LAST_APP_CTX = appCtx;
            installCrashGuard(appCtx);
            sanitizeExistingRepoDbOnce(appCtx);
        }
    }

    /**
     * Installs a process-wide uncaught exception handler the first time a repo sync starts.
     * Any hard crash after that (including `OutOfMemoryError`, which Android 4.2.2's
     * `UpdateService catch (Exception)` cannot see) is written to
     * `files/arc7-last-crash.txt` — readable from the Kobo Arc 7 Freedom app via root —
     * and mirrored to logcat before the previous handler runs.
     */
    public static void installCrashGuard(final Context ctx) {
        if (ctx == null || !CRASH_GUARD_INSTALLED.compareAndSet(false, true)) return;
        try {
            final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable error) {
                    try {
                        writeCrashLog(appCtx, thread, error);
                    } catch (Throwable ignored) {
                    }
                    if (previous != null) previous.uncaughtException(thread, error);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public static File crashLogFile(Context ctx) {
        if (ctx == null) return null;
        return new File(ctx.getFilesDir(), CRASH_LOG_NAME);
    }

    private static void writeCrashLog(Context ctx, Thread thread, Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        String text = "F-Droid Classic Android 4.2 crash " + new Date().toString()
                + "\\nthread: " + thread.getName() + "\\n" + sw.toString();
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(ctx.getFilesDir(), CRASH_LOG_NAME));
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try { out.close(); } catch (Throwable ignored) {}
            }
        }
        Log.e("Arc7CrashGuard", text);
    }

    /**
     * Runs `UpdateService.notifyContentProviders()` from `onHandleIntent()` through a
     * `Throwable` guard.  The call site after the repo sync lives OUTSIDE every
     * `try` range of `onHandleIntent`, so any `RuntimeException` thrown there (a
     * `SQLiteException` on an old database, an `IllegalArgumentException` from
     * `App(Cursor)` / `checkCursorPosition()`, a `NullPointerException` inside
     * `NotificationCompat`, …) used to kill the whole F-Droid process the instant the
     * sync finished — exactly the "syncs then crashes" report.
     *
     * `patch_update_service_safe_post_sync()` retargets both post-sync calls to these
     * wrappers, so a broken notification / refresh step now only loses the toast/refresh
     * while F-Droid stays open, and the full stack trace is appended to
     * `files/arc7-last-crash.txt`.
     */
    public static void safeNotifyContentProviders(Object service) {
        invokeServiceMethod(service, "notifyContentProviders");
    }

    public static void safePerformUpdateNotification(Object service) {
        invokeServiceMethod(service, "performUpdateNotification");
    }

    private static void invokeServiceMethod(Object service, String methodName) {
        if (service == null) return;
        try {
            Method method = service.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(service);
        } catch (Throwable error) {
            Throwable cause = error;
            if (error instanceof InvocationTargetException
                    && ((InvocationTargetException) error).getCause() != null) {
                cause = ((InvocationTargetException) error).getCause();
            }
            logCaught("UpdateService." + methodName, cause);
        }
    }

    /** Appends a caught-but-non-fatal `Throwable` to `files/arc7-last-crash.txt` + logcat. */
    public static void logCaught(String where, Throwable error) {
        StringWriter sw = new StringWriter();
        sw.write("F-Droid Classic Android 4.2 caught error in " + where + " at "
                + new Date().toString() + "\\n");
        if (error != null) {
            error.printStackTrace(new PrintWriter(sw));
        }
        String text = sw.toString();
        Log.e("Arc7CrashGuard", text);
        Context ctx = LAST_APP_CTX;
        if (ctx == null) return;
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(ctx.getFilesDir(), CRASH_LOG_NAME), true);
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try { out.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Self-heals any pre-`v1303` SQLite `fdroid_repo` table in-place (if `databases/fdroid`
     * already exists on disk) so even in-place APK upgrades without `pm clear` immediately
     * have clean TLS-1.3-bridged repo URLs, disabled `bubu1.eu`, and cleared broken etags!
     */
    private static void sanitizeExistingRepoDbOnce(Context appCtx) {
        if (!DB_SANITIZED.compareAndSet(false, true)) return;
        SQLiteDatabase db = null;
        try {
            File dbFile = appCtx.getDatabasePath("fdroid");
            if (dbFile != null && dbFile.isFile() && dbFile.length() > 0) {
                db = SQLiteDatabase.openDatabase(
                        dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
                db.execSQL(
                        "UPDATE fdroid_repo SET address = '" + LOCAL_BRIDGE_PREFIX + "' || substr(address, 9) "
                        + "WHERE address LIKE 'https://%'");
                db.execSQL(
                        "UPDATE fdroid_repo SET inuse = 0 WHERE address LIKE '%bubu1.eu%' OR address LIKE '%briarproject.org%'");
                db.execSQL(
                        "UPDATE fdroid_repo SET inuse = 1 WHERE address LIKE '%/f-droid.org/repo'");
            }
        } catch (Throwable ignored) {
        } finally {
            if (db != null) {
                try { db.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public static String rewriteUrlString(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            return LOCAL_BRIDGE_PREFIX + trimmed.substring(8);
        }
        return trimmed;
    }

    public static HttpURLConnection openConnection(URL url, Proxy explicitProxy) throws IOException {
        if (url == null) throw new IOException("Null URL passed to F-Droid HTTP bridge");
        String s = url.toString();
        String rewritten = rewriteUrlString(s);
        URL target = rewritten.equals(s) ? url : new URL(rewritten);
        String ts = target.toString();
        if (!ts.startsWith("http://") && !ts.startsWith("https://")) {
            throw new IOException("Unsupported scheme for F-Droid HTTP bridge: " + ts);
        }
        java.net.URLConnection raw = (ts.startsWith("http://127.0.0.1:") || ts.startsWith("http://localhost:"))
                ? target.openConnection(Proxy.NO_PROXY)
                : (explicitProxy != null ? target.openConnection(explicitProxy) : target.openConnection());
        if (!(raw instanceof HttpURLConnection)) {
            throw new IOException("Non-HTTP connection for " + ts);
        }
        HttpURLConnection conn = (HttpURLConnection) raw;
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private static void collectCandidateFiles(File dir, List<File> out, int depth) {
        if (dir == null || depth > 3) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isFile() && f.length() >= 128) {
                out.add(f);
            } else if (f.isDirectory()) {
                collectCandidateFiles(f, out, depth + 1);
            }
        }
    }

    /**
     * Locates the exact downloaded `index-v1.jar` file in `context.getCacheDir()` /
     * `context.getExternalCacheDir()` matching `jarEntry.getSize()` + `jarEntry.getCrc()`
     * (newest `lastModified()` first) and cryptographically verifies its full
     * `MANIFEST.MF` + `.SF` + BER/DER PKCS#7 RSA/ECDSA/DSA signature!
     */
    public static X509Certificate verifyIndexJarFallback(JarEntry jarEntry) throws SecurityException {
        Context ctx = CURRENT_CTX.get();
        if (ctx == null) ctx = LAST_APP_CTX;
        if (ctx == null) {
            throw new SecurityException("No context registered for PKCS#7 index verification");
        }
        List<File> candidates = new ArrayList<File>();
        collectCandidateFiles(ctx.getCacheDir(), candidates, 0);
        try {
            collectCandidateFiles(ctx.getExternalCacheDir(), candidates, 0);
        } catch (Throwable ignored) {}
        if (candidates.isEmpty()) {
            throw new SecurityException("Cache directory empty during index verification");
        }
        Collections.sort(candidates, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long la = a.lastModified();
                long lb = b.lastModified();
                return lb > la ? 1 : (lb < la ? -1 : 0);
            }
        });

        long wantSize = jarEntry.getSize();
        long wantCrc = jarEntry.getCrc();
        String entryName = jarEntry.getName();

        SecurityException lastErr = null;
        for (int pass = 0; pass < 2; pass++) {
            for (File f : candidates) {
                ZipFile zf = null;
                boolean matches = false;
                try {
                    zf = new ZipFile(f);
                    ZipEntry ze = zf.getEntry(entryName);
                    if (ze != null) {
                        if (pass == 0) {
                            matches = (wantSize < 0 || ze.getSize() < 0 || ze.getSize() == wantSize)
                                    && (wantCrc < 0 || ze.getCrc() < 0 || ze.getCrc() == wantCrc);
                        } else {
                            matches = true;
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (zf != null) { try { zf.close(); } catch (Throwable ignored) {} }
                }
                if (matches) {
                    try {
                        return Arc7ApkKit.verifyPkcs7JarAndExtractCert(f, entryName);
                    } catch (SecurityException se) {
                        lastErr = se;
                    }
                }
            }
        }
        if (lastErr != null) throw lastErr;
        throw new SecurityException("Downloaded index JAR not found in cache for verification");
    }

    /**
     * Android 4.2.2-safe replacement for the `new JarFile(installedFile)` tail of `App.initInstalledApk`:
     * populates `apk.nativecode` via `ZipFile` and `apk.sig` (`MD5(lowercaseHex(certDer)).toUpperCase(Locale.ENGLISH)`)
     * without ever invoking Android 4.2.2 Harmony `JarUtils.verifySignature()`!
     */
    public static void populateInstalledApkInfo(Apk apk, PackageInfo packageInfo, File installedFile)
            throws IOException, CertificateEncodingException {
        HashSet<String> abis = new HashSet<String>(3);
        ZipFile zf = new ZipFile(installedFile);
        try {
            if (zf.getEntry("AndroidManifest.xml") == null) {
                throw new CertificateEncodingException("null signed entry!");
            }
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                Matcher m = ABI_PATTERN.matcher(ze.getName());
                if (m.matches()) {
                    abis.add(m.group(1));
                }
            }
        } finally {
            try { zf.close(); } catch (Throwable ignored) {}
        }
        apk.nativecode = abis.toArray(new String[0]);

        byte[] certDer = null;
        try {
            X509Certificate cert = Arc7ApkKit.verifyPkcs7JarAndExtractCert(installedFile, "AndroidManifest.xml");
            if (cert != null) certDer = cert.getEncoded();
        } catch (Throwable ignored) {}
        if (certDer == null && packageInfo != null && packageInfo.signatures != null && packageInfo.signatures.length > 0) {
            certDer = packageInfo.signatures[0].toByteArray();
        }
        if (certDer == null || certDer.length == 0) {
            throw new CertificateEncodingException("No Certificates found!");
        }
        byte[] hexAscii = new byte[certDer.length * 2];
        for (int i = 0; i < certDer.length; i++) {
            int b = certDer[i] & 0xFF;
            int hi = b >>> 4;
            int lo = b & 0x0F;
            hexAscii[i * 2] = (byte) (hi >= 10 ? ('a' + hi - 10) : ('0' + hi));
            hexAscii[i * 2 + 1] = (byte) (lo >= 10 ? ('a' + lo - 10) : ('0' + lo));
        }
        try {
            byte[] md5 = MessageDigest.getInstance("MD5").digest(hexAscii);
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < md5.length; i++) {
                int b = md5[i] & 0xFF;
                int hi = b >>> 4;
                int lo = b & 0x0F;
                sb.append((char) (hi >= 10 ? ('A' + hi - 10) : ('0' + hi)));
                sb.append((char) (lo >= 10 ? ('A' + lo - 10) : ('0' + lo)));
            }
            apk.sig = sb.toString();
        } catch (Throwable t) {
            throw new CertificateEncodingException(t.getMessage());
        }
    }

    public interface FallbackUiLauncher {
        void onFallbackToSystemInstaller(Uri repairedFileUri);
        void onInstallFailed(String errorMessage);
    }

    public static void installApkSmartAsync(final Activity activity,
                                            final Uri localApkUri,
                                            final DefaultInstaller installer,
                                            final Uri downloadUri,
                                            final FallbackUiLauncher fallback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String path = localApkUri.getPath();
                    if (path == null || path.length() == 0) {
                        throw new IOException("Empty local APK path: " + localApkUri);
                    }
                    File rawApk = new File(path);
                    Arc7ApkKit.ApkInspection info = Arc7ApkKit.inspectApk(rawApk, 17);
                    boolean allowReplace = info != null && !"eu.bubu1.fdroidclassic.debug".equals(info.packageName);
                    final Arc7ApkKit.RootInstallOutcome out =
                            Arc7ApkKit.stageAndInstallApk(activity, rawApk, allowReplace);
                    if (out.succeeded) {
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                installer.sendBroadcastInstall(
                                        downloadUri,
                                        "org.fdroid.fdroid.installer.Installer.action.INSTALL_COMPLETE");
                                activity.finish();
                            }
                        });
                        return;
                    }
                    final Uri readyUri = Uri.fromFile(out.worldReadableFallbackApk);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            fallback.onFallbackToSystemInstaller(readyUri);
                        }
                    });
                } catch (final Throwable t) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            fallback.onInstallFailed(t.getMessage() != null ? t.getMessage() : t.toString());
                        }
                    });
                }
            }
        }, "Arc7FDroidInstaller").start();
    }
}
"""),
        write_java(impl_dir, "info/guardianproject/netcipher/NetCipher.java", """
package info.guardianproject.netcipher;

import android.net.Uri;
import android.os.Build;
import info.guardianproject.netcipher.client.TlsOnlySocketFactory;
import info.guardianproject.netcipher.proxy.OrbotHelper;
import org.fdroid.fdroid.Arc7Bridge;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import javax.net.ssl.HttpsURLConnection;

public class NetCipher {
    public static final Proxy ORBOT_HTTP_PROXY =
            new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 8118));
    public static final Proxy ORBOT_SOCKS_PROXY =
            new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", 9050));
    private static final String TAG = "NetCipher";
    private static Proxy proxy;

    private NetCipher() {}

    public static void setProxy(String host, int port) {
        if (host != null && host.length() > 0 && port > 0) {
            setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
        } else {
            clearProxy();
        }
    }

    public static void setProxy(Proxy p) { proxy = p; }
    public static Proxy getProxy() { return proxy; }
    public static void clearProxy() { proxy = null; }
    public static void useTor() { setProxy(ORBOT_HTTP_PROXY); }
    public static void useGlobalProxy() { clearProxy(); }

    private static boolean checkIsTor(URLConnection conn) { return false; }
    public static boolean isURLConnectionUsingTor() { return false; }
    public static boolean isNetCipherGetHttpURLConnectionUsingTor() { return false; }

    public static TlsOnlySocketFactory getTlsOnlySocketFactory() {
        return getTlsOnlySocketFactory(false);
    }

    public static TlsOnlySocketFactory getTlsOnlySocketFactory(boolean compatible) {
        return null;
    }

    public static HttpURLConnection getCompatibleHttpURLConnection(URL url) throws IOException {
        return getHttpURLConnection(url, true);
    }

    public static HttpsURLConnection getCompatibleHttpsURLConnection(URL url) throws IOException {
        return getHttpsURLConnection(url, true);
    }

    public static HttpURLConnection getHttpURLConnection(Uri uri) throws IOException {
        return getHttpURLConnection(uri.toString());
    }

    public static HttpURLConnection getHttpURLConnection(String urlString) throws IOException {
        return getHttpURLConnection(new URL(Arc7Bridge.rewriteUrlString(urlString)));
    }

    public static HttpURLConnection getHttpURLConnection(URI uri) throws IOException {
        return getHttpURLConnection(uri.toURL());
    }

    public static HttpURLConnection getHttpURLConnection(URL url) throws IOException {
        return getHttpURLConnection(url, false);
    }

    public static HttpURLConnection getHttpURLConnection(URL url, boolean shouldFallbackIfTorNotAvailable)
            throws IOException {
        Proxy p = proxy;
        if (OrbotHelper.isOnionAddress(url)) {
            p = Build.VERSION.SDK_INT < 24 ? ORBOT_HTTP_PROXY : ORBOT_SOCKS_PROXY;
        }
        return Arc7Bridge.openConnection(url, p);
    }

    public static HttpsURLConnection getHttpsURLConnection(Uri uri) throws IOException {
        return getHttpsURLConnection(uri.toString());
    }

    public static HttpsURLConnection getHttpsURLConnection(String urlString) throws IOException {
        return getHttpsURLConnection(new URL(urlString), false);
    }

    public static HttpsURLConnection getHttpsURLConnection(URI uri) throws IOException {
        return getHttpsURLConnection(uri.toURL(), false);
    }

    public static HttpsURLConnection getHttpsURLConnection(URL url) throws IOException {
        return getHttpsURLConnection(url, false);
    }

    public static HttpsURLConnection getHttpsURLConnection(URL url, boolean compatible) throws IOException {
        return (HttpsURLConnection) url.openConnection();
    }
}
"""),
        write_java(impl_dir, "com/nostra13/universalimageloader/core/download/BaseImageDownloader.java", """
package com.nostra13.universalimageloader.core.download;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.webkit.MimeTypeMap;
import com.nostra13.universalimageloader.core.assist.ContentLengthInputStream;
import com.nostra13.universalimageloader.utils.IoUtils;
import org.fdroid.fdroid.Arc7Bridge;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class BaseImageDownloader implements ImageDownloader {
    public static final int DEFAULT_HTTP_CONNECT_TIMEOUT = 5000;
    public static final int DEFAULT_HTTP_READ_TIMEOUT = 20000;
    protected static final int BUFFER_SIZE = 32768;
    protected static final String ALLOWED_URI_CHARS = "@#&=*+-_.,:!?()/~'%";
    protected static final int MAX_REDIRECT_COUNT = 5;
    protected static final String CONTENT_CONTACTS_URI_PREFIX = "content://com.android.contacts/";
    private static final String ERROR_UNSUPPORTED_SCHEME =
            "UIL doesn't support scheme(protocol) by default [%s]. You should implement this support yourself (BaseImageDownloader.getStreamFromOtherSource(...))";

    protected final Context context;
    protected final int connectTimeout;
    protected final int readTimeout;

    public BaseImageDownloader(Context context) {
        this(context, DEFAULT_HTTP_CONNECT_TIMEOUT, DEFAULT_HTTP_READ_TIMEOUT);
    }

    public BaseImageDownloader(Context context, int connectTimeout, int readTimeout) {
        this.context = context.getApplicationContext();
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Override
    public InputStream getStream(String imageUri, Object extra) throws IOException {
        switch (Scheme.ofUri(imageUri)) {
            case HTTP:
            case HTTPS:
                return getStreamFromNetwork(imageUri, extra);
            case FILE:
                return getStreamFromFile(imageUri, extra);
            case CONTENT:
                return getStreamFromContent(imageUri, extra);
            case ASSETS:
                return getStreamFromAssets(imageUri, extra);
            case DRAWABLE:
                return getStreamFromDrawable(imageUri, extra);
            case UNKNOWN:
            default:
                return getStreamFromOtherSource(imageUri, extra);
        }
    }

    protected InputStream getStreamFromNetwork(String imageUri, Object extra) throws IOException {
        HttpURLConnection conn = createConnection(imageUri, extra);
        int redirectCount = 0;
        while (conn.getResponseCode() / 100 == 3 && redirectCount < MAX_REDIRECT_COUNT) {
            conn = createConnection(conn.getHeaderField("Location"), extra);
            redirectCount++;
        }
        InputStream imageStream;
        try {
            imageStream = conn.getInputStream();
        } catch (IOException e) {
            IoUtils.readAndCloseStream(conn.getErrorStream());
            throw e;
        }
        if (!shouldBeProcessed(conn)) {
            IoUtils.closeSilently(imageStream);
            throw new IOException("Image request failed with response code " + conn.getResponseCode());
        }
        return new ContentLengthInputStream(new BufferedInputStream(imageStream, BUFFER_SIZE), conn.getContentLength());
    }

    protected boolean shouldBeProcessed(HttpURLConnection conn) throws IOException {
        return conn.getResponseCode() == 200;
    }

    protected HttpURLConnection createConnection(String url, Object extra) throws IOException {
        try {
            String encodedUrl = Uri.encode(url, ALLOWED_URI_CHARS);
            HttpURLConnection conn = Arc7Bridge.openConnection(new URL(encodedUrl), null);
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            return conn;
        } catch (IOException ioe) {
            throw ioe;
        } catch (Throwable t) {
            // UIL only ever catches IOException; convert any unchecked bridge failure
            // (ClassCastException / IllegalArgumentException / ...) so icon loading can
            // never force-close F-Droid on Android 4.2.
            throw new IOException("Image connection failed: " + t);
        }
    }

    protected InputStream getStreamFromFile(String imageUri, Object extra) throws IOException {
        String filePath = Scheme.FILE.crop(imageUri);
        if (isVideoFileUri(imageUri)) {
            return getVideoThumbnailStream(filePath);
        }
        BufferedInputStream imageStream = new BufferedInputStream(new FileInputStream(filePath), BUFFER_SIZE);
        return new ContentLengthInputStream(imageStream, (int) new File(filePath).length());
    }

    private InputStream getVideoThumbnailStream(String filePath) {
        if (Build.VERSION.SDK_INT >= 8) {
            Bitmap bitmap = ThumbnailUtils.createVideoThumbnail(filePath, 2);
            if (bitmap != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 0, bos);
                return new ByteArrayInputStream(bos.toByteArray());
            }
        }
        return null;
    }

    protected InputStream getStreamFromContent(String imageUri, Object extra) throws FileNotFoundException {
        ContentResolver res = context.getContentResolver();
        Uri uri = Uri.parse(imageUri);
        if (isVideoContentUri(uri)) {
            return res.openInputStream(uri);
        } else if (imageUri.startsWith(CONTENT_CONTACTS_URI_PREFIX)) {
            return getContactPhotoStream(uri);
        }
        return res.openInputStream(uri);
    }

    protected InputStream getContactPhotoStream(Uri uri) {
        ContentResolver res = context.getContentResolver();
        if (Build.VERSION.SDK_INT >= 14) {
            return ContactsContract.Contacts.openContactPhotoInputStream(res, uri, true);
        }
        return ContactsContract.Contacts.openContactPhotoInputStream(res, uri);
    }

    protected InputStream getStreamFromAssets(String imageUri, Object extra) throws IOException {
        String filePath = Scheme.ASSETS.crop(imageUri);
        return context.getAssets().open(filePath);
    }

    protected InputStream getStreamFromDrawable(String imageUri, Object extra) {
        String drawableIdString = Scheme.DRAWABLE.crop(imageUri);
        int drawableId = Integer.parseInt(drawableIdString);
        return context.getResources().openRawResource(drawableId);
    }

    protected InputStream getStreamFromOtherSource(String imageUri, Object extra) throws IOException {
        throw new UnsupportedOperationException(String.format(ERROR_UNSUPPORTED_SCHEME, imageUri));
    }

    private boolean isVideoContentUri(Uri uri) {
        String mimeType = context.getContentResolver().getType(uri);
        return mimeType != null && mimeType.startsWith("video/");
    }

    private boolean isVideoFileUri(String uri) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(uri);
        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mimeType != null && mimeType.startsWith("video/");
    }
}
"""),
        write_java(impl_dir, "org/fdroid/fdroid/IndexUpdater.java", """
package org.fdroid.fdroid;

import android.content.Context;
import org.fdroid.fdroid.data.Repo;

import java.security.CodeSigner;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.jar.JarEntry;

public abstract class IndexUpdater {
    private static final String TAG = "IndexUpdater";
    final Context context;
    final Repo repo;
    final String indexUrl;
    boolean hasChanged;
    final ProgressListener downloadListener;
    protected final ProgressListener processIndexListener;

    public IndexUpdater(Context context, Repo repo) {
        Arc7Bridge.registerCurrentContext(context);
        this.downloadListener = new ProgressListener() {
            @Override
            public void onProgress(String urlString, long bytesRead, long totalBytes) {
                UpdateService.reportDownloadProgress(IndexUpdater.this.context, IndexUpdater.this, bytesRead, totalBytes);
            }
        };
        this.processIndexListener = new ProgressListener() {
            @Override
            public void onProgress(String urlString, long bytesRead, long totalBytes) {
                UpdateService.reportProcessIndexProgress(IndexUpdater.this.context, IndexUpdater.this, bytesRead, totalBytes);
            }
        };
        this.context = context;
        this.repo = repo;
        this.indexUrl = getIndexUrl(repo);
    }

    protected abstract String getIndexUrl(Repo repo);

    boolean hasChanged() {
        return hasChanged;
    }

    void notifyProcessingApps(int appsSaved, int totalApps) {
        UpdateService.reportProcessingAppsProgress(context, this, appsSaved, totalApps);
    }

    void notifyCommittingToDb() {
        notifyProcessingApps(0, -1);
    }

    static X509Certificate getSigningCertFromJar(JarEntry jarEntry) throws IndexUpdater$SigningException {
        final CodeSigner[] codeSigners = jarEntry.getCodeSigners();
        if (codeSigners != null && codeSigners.length > 0) {
            if (codeSigners.length > 1) {
                throw new IndexUpdater$SigningException("index-v1.jar must be signed by a single code signer!");
            }
            List<? extends Certificate> certs = codeSigners[0].getSignerCertPath().getCertificates();
            if (certs.size() != 1) {
                throw new IndexUpdater$SigningException("index-v1.jar code signers must only have a single certificate!");
            }
            return (X509Certificate) certs.get(0);
        }
        // Android <= 4.2.2 AOSP Bug #38321: `JarVerifier` returns `null` when a 2026 repository
        // signs `index-v1.jar` with `SHA-256-Digest` + `SHA256withRSA`. Perform full PKCS#7 verification!
        try {
            return Arc7Bridge.verifyIndexJarFallback(jarEntry);
        } catch (SecurityException se) {
            throw new IndexUpdater$SigningException("No valid signature found in index: " + se.getMessage());
        }
    }
}
"""),
        write_java(impl_dir, "org/fdroid/fdroid/installer/DefaultInstallerActivity.java", """
package org.fdroid.fdroid.installer;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import androidx.fragment.app.FragmentActivity;
import org.fdroid.fdroid.Arc7Bridge;
import org.fdroid.fdroid.data.Apk;

public class DefaultInstallerActivity extends FragmentActivity {
    private static final String TAG = "DefaultInstallerActivit";
    static final String ACTION_INSTALL_PACKAGE = "org.fdroid.fdroid.installer.DefaultInstaller.action.INSTALL_PACKAGE";
    static final String ACTION_UNINSTALL_PACKAGE = "org.fdroid.fdroid.installer.DefaultInstaller.action.UNINSTALL_PACKAGE";
    private static final int REQUEST_CODE_INSTALL = 0;
    private static final int REQUEST_CODE_UNINSTALL = 1;

    private DefaultInstaller installer;
    private Uri downloadUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        String action = intent.getAction();
        Apk apk = (Apk) intent.getParcelableExtra("org.fdroid.fdroid.installer.Installer.extra.APK");
        this.installer = new DefaultInstaller(this, apk);

        if (ACTION_INSTALL_PACKAGE.equals(action)) {
            Uri localApkUri = intent.getData();
            this.downloadUri = (Uri) intent.getParcelableExtra("org.fdroid.fdroid.installer.Installer.extra.DOWNLOAD_URI");
            installPackage(localApkUri);
        } else if (ACTION_UNINSTALL_PACKAGE.equals(action)) {
            uninstallPackage(apk.packageName);
        } else {
            throw new IllegalStateException("Intent action not specified!");
        }
    }

    private void installPackage(final Uri localApkUri) {
        if (localApkUri == null) {
            throw new RuntimeException("Set the data uri to point to an apk location!");
        }
        Arc7Bridge.installApkSmartAsync(
                this,
                localApkUri,
                installer,
                downloadUri,
                new Arc7Bridge.FallbackUiLauncher() {
                    @Override
                    public void onFallbackToSystemInstaller(Uri repairedFileUri) {
                        launchSystemPackageInstaller(repairedFileUri);
                    }

                    @Override
                    public void onInstallFailed(String errorMessage) {
                        installer.sendBroadcastInstall(
                                downloadUri,
                                "org.fdroid.fdroid.installer.Installer.action.INSTALL_INTERRUPTED",
                                errorMessage);
                        finish();
                    }
                });
    }

    void launchSystemPackageInstaller(Uri uri) {
        Intent intent = new Intent();
        intent.setAction("android.intent.action.INSTALL_PACKAGE");
        intent.setData(uri);
        if (Build.VERSION.SDK_INT >= 24) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        intent.putExtra("android.intent.extra.RETURN_RESULT", true);
        intent.putExtra("android.intent.extra.NOT_UNKNOWN_SOURCE", true);
        try {
            startActivityForResult(intent, REQUEST_CODE_INSTALL);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "ActivityNotFoundException", e);
            installer.sendBroadcastInstall(
                    downloadUri,
                    "org.fdroid.fdroid.installer.Installer.action.INSTALL_INTERRUPTED",
                    "This Android rom does not support ACTION_INSTALL_PACKAGE!");
            finish();
        }
    }

    private void uninstallPackage(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "NameNotFoundException", e);
            installer.sendBroadcastUninstall(
                    "org.fdroid.fdroid.installer.Installer.action.UNINSTALL_INTERRUPTED",
                    "Package that is scheduled for uninstall is not installed!");
            finish();
            return;
        }
        Uri uri = Uri.fromParts("package", packageName, null);
        Intent intent = new Intent();
        intent.setData(uri);
        intent.setAction("android.intent.action.UNINSTALL_PACKAGE");
        intent.putExtra("android.intent.extra.RETURN_RESULT", true);
        try {
            startActivityForResult(intent, REQUEST_CODE_UNINSTALL);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "ActivityNotFoundException", e);
            installer.sendBroadcastUninstall(
                    "org.fdroid.fdroid.installer.Installer.action.UNINSTALL_INTERRUPTED",
                    "This Android rom does not support ACTION_UNINSTALL_PACKAGE!");
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_CODE_INSTALL:
                if (resultCode == RESULT_OK) {
                    installer.sendBroadcastInstall(
                            downloadUri,
                            "org.fdroid.fdroid.installer.Installer.action.INSTALL_COMPLETE");
                } else if (resultCode == RESULT_CANCELED) {
                    installer.sendBroadcastInstall(
                            downloadUri,
                            "org.fdroid.fdroid.installer.Installer.action.INSTALL_INTERRUPTED");
                } else {
                    installer.sendBroadcastInstall(
                            downloadUri,
                            "org.fdroid.fdroid.installer.Installer.action.INSTALL_INTERRUPTED",
                            getString(2131886239));
                }
                break;
            case REQUEST_CODE_UNINSTALL:
                if (resultCode == RESULT_OK) {
                    installer.sendBroadcastUninstall(
                            "org.fdroid.fdroid.installer.Installer.action.UNINSTALL_COMPLETE");
                } else if (resultCode == RESULT_CANCELED) {
                    installer.sendBroadcastUninstall(
                            "org.fdroid.fdroid.installer.Installer.action.UNINSTALL_INTERRUPTED");
                } else {
                    installer.sendBroadcastUninstall(
                            "org.fdroid.fdroid.installer.Installer.action.UNINSTALL_INTERRUPTED",
                            getString(2131886518));
                }
                break;
            default:
                throw new RuntimeException("Invalid request code!");
        }
        finish();
    }
}
"""),
    ]
    return stub_files, impl_files


def _resolve_tool_paths(java_bin: str):
    import glob as _glob
    ecj_jar = os.environ.get("ECJ", "/opt/toolchain/jars/ecj-3.45.0.jar")
    if not os.path.exists(ecj_jar):
        ecj_jar = ""
    javac_bin = os.path.join(os.path.dirname(java_bin), "javac")
    if not os.path.exists(javac_bin):
        javac_bin = "javac"

    d8_candidates = [os.environ.get("D8_JAR", ""), "/opt/toolchain/jars/d8.jar"]
    for envk in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(envk, "")
        if root:
            d8_candidates.extend(sorted(_glob.glob(os.path.join(root, "build-tools", "*", "lib", "d8.jar")), reverse=True))
    d8_jar = next((p for p in d8_candidates if p and os.path.exists(p)), "")
    assert d8_jar, "Could not locate d8.jar"

    aj_candidates = [os.environ.get("ANDROID_JAR", ""), "/opt/toolchain/sdk/platforms/android-17/android.jar"]
    for envk in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(envk, "")
        if root:
            aj_candidates.extend(sorted(_glob.glob(os.path.join(root, "platforms", "android-*", "android.jar"))))
    android_jar = next((p for p in aj_candidates if p and os.path.exists(p)), "")
    assert android_jar, "Could not locate android.jar"
    return ecj_jar, javac_bin, d8_jar, android_jar


def _compile_java_sources(java_bin: str, ecj_jar: str, javac_bin: str, android_jar: str,
                          classpath: str, out_dir: str, srcs: list) -> None:
    if ecj_jar:
        cmd = [java_bin, "-jar", ecj_jar, "-1.8", "-source", "1.8", "-target", "1.8",
               "-bootclasspath", android_jar]
        if classpath:
            cmd += ["-classpath", classpath]
        cmd += ["-d", out_dir, *srcs]
    else:
        cmd = [javac_bin, "-source", "1.8", "-target", "1.8",
               "-bootclasspath", android_jar, "-Xlint:none"]
        if classpath:
            cmd += ["-classpath", classpath]
        cmd += ["-d", out_dir, *srcs]
    subprocess.run(cmd, check=True)


def compile_dex1_with_arc7_bridge(raw_stripped_dex1: bytes, tmp_dir: str, repo_root: str, java_bin: str) -> bytes:
    ecj_jar, javac_bin, d8_jar, android_jar = _resolve_tool_paths(java_bin)

    stub_srcs, impl_srcs = build_fdroid_patch_sources(os.path.join(tmp_dir, "java_src"), repo_root)
    stub_cls = os.path.join(tmp_dir, "stub_classes")
    impl_cls = os.path.join(tmp_dir, "impl_classes")
    dex_out = os.path.join(tmp_dir, "dex1_merged_out")
    os.makedirs(stub_cls, exist_ok=True)
    os.makedirs(impl_cls, exist_ok=True)
    os.makedirs(dex_out, exist_ok=True)

    # First compile stubs + impl together into `stub_cls` to satisfy cyclic type refs cleanly
    _compile_java_sources(java_bin, ecj_jar, javac_bin, android_jar, "", stub_cls, [*stub_srcs, *impl_srcs])
    # Then compile ONLY `impl_srcs` into clean `impl_cls` (`stub_cls` on `-classpath` ONLY!)
    _compile_java_sources(java_bin, ecj_jar, javac_bin, android_jar, stub_cls, impl_cls, impl_srcs)

    impl_jar = os.path.join(tmp_dir, "impl_only.jar")
    with zipfile.ZipFile(impl_jar, "w", compression=zipfile.ZIP_DEFLATED) as zj:
        for root, _dirs, files in os.walk(impl_cls):
            for fn in sorted(files):
                if fn.endswith(".class"):
                    full = os.path.join(root, fn)
                    zj.write(full, os.path.relpath(full, impl_cls))

    in_dex1 = os.path.join(tmp_dir, "dex1_stripped.dex")
    with open(in_dex1, "wb") as fh:
        fh.write(raw_stripped_dex1)

    subprocess.run(
        [java_bin, "-cp", d8_jar, "com.android.tools.r8.D8",
         "--release", "--min-api", "15", "--lib", android_jar, "--classpath", stub_cls,
         "--output", dex_out, in_dex1, impl_jar],
        check=True,
    )
    assert not os.path.exists(os.path.join(dex_out, "classes2.dex")), "Primary classes.dex overflowed 64K methods!"
    with open(os.path.join(dex_out, "classes.dex"), "rb") as fh:
        dex_bytes = fh.read()
    assert dex_bytes[:8] == b"dex\n035\x00", f"Unexpected DEX header: {dex_bytes[:8]!r}"
    return dex_bytes


def canonicalize_stripped_dex(raw_stripped: bytes, tmp_dir: str, tag: str, java_bin: str) -> bytes:
    _ecj, _javac, d8_jar, _aj = _resolve_tool_paths(java_bin)
    in_dex = os.path.join(tmp_dir, f"{tag}_in.dex")
    out_dir = os.path.join(tmp_dir, f"{tag}_out")
    os.makedirs(out_dir, exist_ok=True)
    with open(in_dex, "wb") as fh:
        fh.write(raw_stripped)
    subprocess.run(
        [java_bin, "-cp", d8_jar, "com.android.tools.r8.D8",
         "--release", "--min-api", "15", "--output", out_dir, in_dex],
        check=True,
    )
    with open(os.path.join(out_dir, "classes.dex"), "rb") as fh:
        res = fh.read()
    assert res[:8] == b"dex\n035\x00"
    return res


def patch_fdroid_apk(apk_path: str, zipalign_bin: str, java_bin: str, apksigner_jar: str,
                     keystore_path: str, ks_pass: str, key_alias: str) -> None:
    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

    all_managed_classes = {
        "Linfo/guardianproject/netcipher/NetCipher;",
        "Lcom/nostra13/universalimageloader/core/download/BaseImageDownloader;",
        "Lcom/nostra13/universalimageloader/core/download/BaseImageDownloader$1;",
        "Lorg/fdroid/fdroid/IndexUpdater;",
        "Lorg/fdroid/fdroid/IndexUpdater$1;",
        "Lorg/fdroid/fdroid/IndexUpdater$2;",
        "Lorg/fdroid/fdroid/installer/DefaultInstallerActivity;",
        "Lorg/fdroid/fdroid/installer/DefaultInstallerActivity$1;",
        "Lorg/fdroid/fdroid/Arc7Bridge;",
        "Lorg/fdroid/fdroid/Arc7Bridge$1;",
        "Lorg/fdroid/fdroid/Arc7Bridge$1$1;",
        "Lorg/fdroid/fdroid/Arc7Bridge$1$2;",
        "Lorg/fdroid/fdroid/Arc7Bridge$1$3;",
        "Lorg/fdroid/fdroid/Arc7Bridge$FallbackUiLauncher;",
        "Lorg/fdroid/fdroid/Arc7ApkKit;",
        "Lorg/fdroid/fdroid/Arc7ApkKit$AlignedZipWriter;",
        "Lorg/fdroid/fdroid/Arc7ApkKit$AlignedZipWriter$CdRecord;",
        "Lorg/fdroid/fdroid/Arc7ApkKit$ApkInspection;",
        "Lorg/fdroid/fdroid/Arc7ApkKit$RootInstallOutcome;",
        "Lorg/fdroid/fdroid/Arc7ApkKit$SignerMaterial;",
    }

    with zipfile.ZipFile(apk_path, "r") as zf_in, tempfile.TemporaryDirectory() as tmp:
        manifest_patched = patch_manifest_add_large_heap(
            patch_manifest_version_code(
                zf_in.read("AndroidManifest.xml"), TARGET_FDROID_VERSION_CODE
            )
        )
        arsc_patched, fixed_repos = patch_arsc_bytes(zf_in.read("resources.arsc"))

        dex1_merged = compile_dex1_with_arc7_bridge(
            strip_classes_from_dex(zf_in.read("classes.dex"), all_managed_classes),
            tmp, repo_root, java_bin,
        )
        dex1_clean = canonicalize_stripped_dex(
            patch_app_init_installed_apk_safe(dex1_merged),
            tmp, "dex1_verified", java_bin,
        )
        dex1_clean = canonicalize_stripped_dex(
            patch_update_service_catch_throwable(dex1_clean),
            tmp, "dex1_throwable", java_bin,
        )
        dex1_clean = patch_update_service_safe_post_sync(dex1_clean)
        dex4_clean = canonicalize_stripped_dex(
            patch_indexv1_jarfile_noverify(
                strip_classes_from_dex(zf_in.read("classes4.dex"), all_managed_classes)
            ),
            tmp, "dex4", java_bin,
        )

        unsigned_apk = os.path.join(tmp, "unsigned.apk")
        aligned_apk = os.path.join(tmp, "aligned.apk")

        with zipfile.ZipFile(unsigned_apk, "w") as zf_out:
            for info in zf_in.infolist():
                fn = info.filename
                if fn.startswith("META-INF/") or fn == "classes5.dex":
                    continue
                new_info = zipfile.ZipInfo(filename=fn, date_time=info.date_time)
                new_info.external_attr = info.external_attr
                if fn == "AndroidManifest.xml":
                    new_info.compress_type = info.compress_type
                    zf_out.writestr(new_info, manifest_patched)
                elif fn == "resources.arsc":
                    new_info.compress_type = zipfile.ZIP_STORED
                    zf_out.writestr(new_info, arsc_patched)
                elif fn == "classes.dex":
                    new_info.compress_type = zipfile.ZIP_DEFLATED
                    zf_out.writestr(new_info, dex1_clean)
                elif fn == "classes4.dex":
                    new_info.compress_type = zipfile.ZIP_DEFLATED
                    zf_out.writestr(new_info, dex4_clean)
                else:
                    new_info.compress_type = info.compress_type
                    zf_out.writestr(new_info, zf_in.read(fn))

        env = os.environ.copy()
        env["LD_LIBRARY_PATH"] = "/opt/toolchain/lib64:" + env.get("LD_LIBRARY_PATH", "")
        subprocess.run([zipalign_bin, "-f", "-p", "4", unsigned_apk, aligned_apk], check=True, env=env)
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
        ], check=True, env=env)

    print(
        f"  Patched FDroidClassicLegacy.apk -> v{TARGET_FDROID_VERSION_CODE} "
        f"(fixed_repos={fixed_repos}/7, primary classes.dex=DEX035 Arc7Bridge+SHA256+V1Signer, signed=v1+v2+v3)"
    )


if __name__ == "__main__":
    if len(sys.argv) != 8:
        sys.exit("Usage: patch-fdroid-apk.py <apk> <zipalign> <java> <apksigner.jar> <keystore.jks> <pass> <alias>")
    patch_fdroid_apk(*sys.argv[1:8])
