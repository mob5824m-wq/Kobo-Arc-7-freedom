#!/usr/bin/env python3
"""Bundle every signed default APK offline without duplicating its shared DEX/resources.

Recipes concatenate exact ZIP header/data/trailer byte ranges, including all v1/v2/v3
signatures. No APK is rebuilt or re-signed on the tablet. SHA-256 checks gate both the
host round-trip and on-device extraction. This is storage deduplication, NOT shared UIDs
or a runtime dependency between apps. Generated assets are intentionally gitignored.
"""
from pathlib import Path
import hashlib
import struct
import zipfile

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets"
OUT = ASSETS / "maintenance"


def apk_segments(data):
    """Separate variable local headers from identical compressed ZIP entry bodies."""
    import io
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        cursor = 0
        for info in sorted(archive.infolist(), key=lambda item: item.header_offset):
            offset = info.header_offset
            assert data[offset:offset + 4] == b"PK\x03\x04"
            name_len, extra_len = struct.unpack_from("<HH", data, offset + 26)
            start = offset + 30 + name_len + extra_len
            end = start + info.compress_size
            assert cursor <= start <= end <= len(data)
            if start > cursor:
                yield data[cursor:start]
            if end > start:
                yield data[start:end]
            cursor = end
        if cursor < len(data):
            yield data[cursor:]


def identity(path):
    from androguard.core.axml import AXMLPrinter
    from loguru import logger
    logger.remove()
    with zipfile.ZipFile(path) as archive:
        xml = AXMLPrinter(archive.read("AndroidManifest.xml")).get_xml_obj()
    return xml.get("package"), int(xml.get("{http://schemas.android.com/apk/res/android}versionCode"))


def build():
    import shutil
    if OUT.exists():
        shutil.rmtree(OUT)
    (OUT / "chunks").mkdir(parents=True)
    (OUT / "recipes").mkdir()
    rows = ["# package\tlabel\tfilename\tversionCode\tsha256\tsource"]
    direct = [
        ("Home", "VanillaAndroid4Home.apk"),
        ("F-Droid Classic", "starter-apps/FDroidClassicLegacy.apk"),
        ("Aurora Store", "starter-apps/AuroraStore.apk"),
        ("VLC", "starter-apps/VLC.apk"),
        ("YouTube", "starter-apps/YouTube.apk"),
        ("YouTube Music", "starter-apps/YouTubeMusic.apk"),
    ]
    for label, asset in direct:
        path = ASSETS / asset
        pkg, version = identity(path)
        rows.append("\t".join((pkg, label, path.name, str(version), hashlib.sha256(path.read_bytes()).hexdigest(), "asset:" + asset)))

    original_bytes = 0
    seen_packages = {row.split("\t")[0] for row in rows[1:]}
    for line in (ROOT / "split-apps/apps.tsv").read_text().splitlines():
        key, filename, label, _description = line.split("\t", 3)
        path = ROOT / "releases/apps" / filename
        data = path.read_bytes()
        pkg, version = identity(path)
        assert pkg not in seen_packages, pkg
        seen_packages.add(pkg)
        original_bytes += len(data)
        parts = []
        rebuilt = hashlib.sha256()
        for segment in apk_segments(data):
            digest = hashlib.sha256(segment).hexdigest()
            target = OUT / "chunks" / (digest + ".bin")
            if not target.exists():
                target.write_bytes(segment)
            # Read back what's actually bundled, not just the original input.
            rebuilt.update(target.read_bytes())
            parts.append(digest)
        assert rebuilt.digest() == hashlib.sha256(data).digest(), filename
        recipe = "maintenance/recipes/" + key + ".txt"
        (ASSETS / recipe).write_text("\n".join(parts) + "\n")
        rows.append("\t".join((pkg, label, filename, str(version), rebuilt.hexdigest(), "parts:" + recipe)))
    assert len(seen_packages) == 38, len(seen_packages)
    (OUT / "catalog.tsv").write_text("\n".join(rows) + "\n")
    stored_bytes = sum(path.stat().st_size for path in OUT.rglob("*") if path.is_file())
    print(f"Offline maintenance: {len(seen_packages)} APKs; 32 defaults {original_bytes // 1024} KiB -> {stored_bytes // 1024} KiB deduplicated; every signed APK round-trips exactly")


if __name__ == "__main__":
    build()
