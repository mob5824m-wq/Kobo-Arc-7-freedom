#!/usr/bin/env python3
"""Fail before a long build/upload if Android versions or release paths disagree.

The suite manifest is the version authority. Independently versioned Home, stores
and third-party VLC keep their existing identities. Binary versions/signatures are
still verified by verify-apks.py; --artifacts additionally checks the exact files
and hashes about to be staged/uploaded, including the renamed upgrader.
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
NS = "{http://schemas.android.com/apk/res/android}"
WORKFLOW = ".github/workflows/build-apks.yml"
PATH_CONSUMERS = (
    "scripts/build-all.sh",
    "scripts/verify-apks.py",
    "scripts/test-install-maintenance.py",
    "tools/serve-over-wifi.py",
    "tools/serve-over-wifi.sh",
    "tools/serve-over-wifi.bat",
    "tools/Start-Kobo-WiFi-Server.sh",
    "tools/Start-Kobo-WiFi-Server.bat",
    "README.md",
    "releases/SUITE-FEATURES.md",
    "releases/INSTALL-RUNTIME-FIXES.md",
    "releases/WEATHER-FEATURES.md",
    WORKFLOW,
)
UPGRADER_PATTERN = re.compile(r"Upgrade-My-Kobo-To-v[0-9]+\.[0-9]+\.[0-9]+\.apk")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def release_version(root: Path = ROOT) -> tuple[str, str]:
    manifest = ET.parse(root / "app/src/main/AndroidManifest.xml").getroot()
    version, code = manifest.get(NS + "versionName", ""), manifest.get(NS + "versionCode", "")
    require(bool(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version)), "Suite versionName must be a release version")
    require(code.isdigit() and int(code) > 0, "Suite versionCode must be positive")
    return version, code


def check_sources(root: Path = ROOT) -> tuple[str, str]:
    version, code = release_version(root)
    manifests = [root / "upgrader/src/main/AndroidManifest.xml"]
    manifests += sorted((root / "split-apps").glob("*/AndroidManifest.xml"))
    require(len(manifests) == 35, "Expected upgrader plus all 34 standalone manifests")
    for path in manifests:
        manifest = ET.parse(path).getroot()
        require((manifest.get(NS + "versionName"), manifest.get(NS + "versionCode")) == (version, code),
                f"{path.relative_to(root)} must match suite {version} / {code}")
    for module in ("app", "upgrader"):
        gradle = (root / module / "build.gradle").read_text()
        require(re.search(r'\bversionName\s+"' + re.escape(version) + r'"', gradle) is not None,
                f"{module}/build.gradle versionName differs from manifest")
        require(re.search(r"\bversionCode\s+" + re.escape(code) + r"\b", gradle) is not None,
                f"{module}/build.gradle versionCode differs from manifest")
    filename = f"Upgrade-My-Kobo-To-v{version}.apk"
    for name in PATH_CONSUMERS:
        text = (root / name).read_text()
        paths = set(UPGRADER_PATTERN.findall(text))
        require(paths == {filename}, f"{name}: expected only {filename}, found {sorted(paths)}")
    workflow = (root / WORKFLOW).read_text()
    labels = re.findall(r"\bv([0-9]+\.[0-9]+\.[0-9]+)\b", workflow)
    require(bool(labels) and set(labels) == {version}, "Workflow build, artifact and release labels must match the Android version")
    build = (root / "scripts/build-all.sh").read_text()
    require(build.index('"$PY_BIN" scripts/gen-split-apps.py') < build.index('build_standalone_app "youtube"'),
            "Regenerate manifests before compiling standalone YouTube/Music")
    return version, code


def check_artifacts(root: Path = ROOT) -> None:
    version, _ = check_sources(root)
    expected_upgrader = f"Upgrade-My-Kobo-To-v{version}.apk"
    upgraders = {path.name for path in (root / "releases").glob("Upgrade-My-Kobo-To-v*.apk")}
    require(upgraders == {expected_upgrader}, f"Expected only {expected_upgrader}; found {sorted(upgraders)}")
    entries: dict[str, str] = {}
    for line in (root / "releases/SHA256SUMS.txt").read_text().splitlines():
        parts = line.split()
        require(len(parts) == 2, "Malformed checksum entry")
        digest, name = parts
        require(bool(re.fullmatch(r"[a-f0-9]{64}", digest)), f"Invalid SHA-256 for {name}")
        require(Path(name).name == name and name not in (".", "..") and "\\" not in name,
                f"Unsafe checksum filename: {name}")
        require(name not in entries, f"Duplicate checksum filename: {name}")
        entries[name] = digest
        candidates = [base / name for base in (root / "releases", root / "releases/apps", root / "tools")]
        candidates = [path for path in candidates if path.is_file()]
        require(len(candidates) == 1, f"Missing or ambiguous release artifact: {name}")
        actual = hashlib.sha256(candidates[0].read_bytes()).hexdigest()
        require(actual == digest, f"Checksum mismatch for {name}")
    required = {expected_upgrader, "KoboArc7Freedom.apk", "YouTube.apk", "YouTubeMusic.apk",
                "VLC.apk", "AuroraStore.apk", "FDroidClassicLegacy.apk", "VanillaAndroid4Home.apk",
                "index.html", "logo-banner.png", "qr-transfer.html",
                "Start-PC-Stream-Deck.py", "Start-PC-Stream-Deck.bat", "Start-PC-Stream-Deck.sh",
                "Start-PC-School-VPN-Relay.py", "Start-Chromebook-Wired-Internet.sh", "Start-Kobo-Second-Screen.py",
                "Backup-Kobo-To-Laptop-ADB.bat", "Backup-Kobo-To-Laptop-ADB.sh",
                "Start-Kobo-WiFi-Server.bat", "Start-Kobo-WiFi-Server.sh"}
    apps = [row.split("\t")[1] for row in (root / "split-apps/apps.tsv").read_text().splitlines() if row]
    require(len(apps) == 32 and len(set(apps)) == 32, "Expected 32 distinct standalone default APKs")
    required.update(apps)
    require(required == entries.keys(), f"Release checksum entries differ: missing {sorted(required - entries.keys())}, "
            f"unexpected {sorted(entries.keys() - required)}")
    require({name for name in entries if name.startswith("Upgrade-My-Kobo-To-v")} == {expected_upgrader},
            "Checksum manifest refers to an obsolete upgrader")
    require(expected_upgrader in (root / "releases/index.html").read_text(), "Generated portal has a stale upgrader link")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", action="store_true", help="also verify built release files and checksums")
    args = parser.parse_args()
    try:
        version, code = check_sources()
        if args.artifacts:
            check_artifacts()
    except (ValueError, OSError, ET.ParseError) as error:
        parser.exit(1, f"Release consistency check failed: {error}\n")
    print(f"PASS release consistency: {version} / {code}, 34 standalone manifests, Gradle, workflow and updater paths"
          + (", built files and SHA-256 checksums" if args.artifacts else ""))


if __name__ == "__main__":
    main()
