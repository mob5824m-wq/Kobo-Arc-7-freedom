#!/usr/bin/env python3
"""Offline regressions for release drift and the workflow's pre-staging gate.

Uses text/filesystem fixtures and a recording git command; never pushes, creates
releases or modifies this checkout. Actual APK versions/signatures are checked by
verify-apks.py, not inferred from these fixture payloads.
"""
from __future__ import annotations

import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("release_check", ROOT / "scripts/check-release-version.py")
CHECK = importlib.util.module_from_spec(spec)
spec.loader.exec_module(CHECK)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="arc7-release-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        paths = set(CHECK.PATH_CONSUMERS) | {
            "app/src/main/AndroidManifest.xml", "app/build.gradle", "upgrader/build.gradle",
            "upgrader/src/main/AndroidManifest.xml", "split-apps/apps.tsv", "scripts/check-release-version.py",
        }
        paths.update(str(p.relative_to(ROOT)) for p in (ROOT / "split-apps").glob("*/AndroidManifest.xml"))
        for name in paths:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, path)

    def replace(self, name, old, new):
        path = self.root / name
        text = path.read_text()
        self.assertIn(old, text)
        path.write_text(text.replace(old, new))

    def build_artifacts(self):
        """Known fake bytes: this tests path/hash gates, NOT Android compatibility."""
        version, _ = CHECK.release_version(self.root)
        names = {f"Upgrade-My-Kobo-To-v{version}.apk", "KoboArc7Freedom.apk", "YouTube.apk", "YouTubeMusic.apk",
                 "VLC.apk", "AuroraStore.apk", "FDroidClassicLegacy.apk", "VanillaAndroid4Home.apk", "logo-banner.png", "index.html"}
        app_names = {row.split("\t")[1] for row in (self.root / "split-apps/apps.tsv").read_text().splitlines()}
        names.update(app_names)
        tool_names = {"Start-PC-Stream-Deck.py", "Start-PC-Stream-Deck.bat", "Start-PC-Stream-Deck.sh", "Start-PC-School-VPN-Relay.py",
                      "Start-Chromebook-Wired-Internet.sh", "Start-Kobo-Second-Screen.py", "Backup-Kobo-To-Laptop-ADB.bat",
                      "Backup-Kobo-To-Laptop-ADB.sh", "Start-Kobo-WiFi-Server.bat", "Start-Kobo-WiFi-Server.sh", "qr-transfer.html", "Arc7-Windows.ps1", "Windows-Tools.zip",
                      "WINDOWS-TOOLS.md", "usb-sideload-and-root.bat", "serve-over-wifi.bat"}
        names.update(tool_names)
        lines = []
        for name in sorted(names):
            base = "tools" if name in tool_names else "releases/apps" if name in app_names else "releases"
            path = self.root / base / name
            path.parent.mkdir(parents=True, exist_ok=True)
            if not path.is_file():
                path.write_text(f"Fixture {name}")
            if name == "index.html":
                path.write_text(f'<a href="./Upgrade-My-Kobo-To-v{version}.apk">Upgrade</a>')
            lines.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {name}\n")
        (self.root / "releases/SHA256SUMS.txt").write_text("".join(lines))

    def workflow_stage(self):
        workflow = (self.root / CHECK.WORKFLOW).read_text()
        stage = workflow.split("      - name: Commit refreshed signed", 1)[1]
        stage = textwrap.dedent(stage.split("        run: |\n", 1)[1].split("      - name:", 1)[0])
        # No commit/push from the test. git only records calls before that block.
        stage = stage.split("if ! git diff --cached --quiet;", 1)[0]
        stage = 'git() { printf "%s\\n" "$*" >> "$TRACE"; }\n' + stage
        env = dict(os.environ, TRACE=str(self.root / "git-calls"))
        result = subprocess.run(["bash", "-euo", "pipefail", "-c", stage], cwd=self.root, env=env, capture_output=True, text=True)
        calls = (self.root / "git-calls").read_text()
        return result, calls

    def test_repository_targets_269(self):
        self.assertEqual(CHECK.check_sources(ROOT), ("2.6.9", "269"))
        self.assertEqual(CHECK.check_sources(self.root), ("2.6.9", "269"))

    def test_label_only_version_bump_is_rejected(self):
        # Reproduce the failed run: workflow calls it 2.6.9, build emits 2.6.0.
        self.replace("scripts/build-all.sh", "2.6.9", "2.6.0")
        with self.assertRaisesRegex(ValueError, "scripts/build-all.sh"):
            CHECK.check_sources(self.root)

    def test_gradle_and_each_manifest_must_match(self):
        for name, old, new in (
            ("app/build.gradle", "versionCode 269", "versionCode 260"),
            ("upgrader/build.gradle", 'versionName "2.6.9"', 'versionName "2.6.0"'),
            ("upgrader/src/main/AndroidManifest.xml", 'versionCode="269"', 'versionCode="260"'),
            ("split-apps/youtube/AndroidManifest.xml", 'versionName="2.6.9"', 'versionName="2.6.0"'),
            ("split-apps/ytmusic/AndroidManifest.xml", 'versionCode="269"', 'versionCode="260"'),
            ("split-apps/podcasts/AndroidManifest.xml", 'versionCode="269"', 'versionCode="260"'),
        ):
            with self.subTest(name=name):
                path = self.root / name
                original = path.read_text()
                self.replace(name, old, new)
                with self.assertRaises(ValueError):
                    CHECK.check_sources(self.root)
                path.write_text(original)

    def test_missing_split_manifest_is_rejected(self):
        (self.root / "split-apps/weather/AndroidManifest.xml").unlink()
        with self.assertRaisesRegex(ValueError, "34 standalone"):
            CHECK.check_sources(self.root)

    def test_workflow_labels_and_host_download_paths_cannot_drift(self):
        for name in (CHECK.WORKFLOW, "tools/serve-over-wifi.py", "tools/Start-Kobo-WiFi-Server.sh",
                     "scripts/test-install-maintenance.py"):
            with self.subTest(name=name):
                path = self.root / name
                original = path.read_text()
                self.replace(name, "2.6.9", "2.6.0")
                with self.assertRaises(ValueError):
                    CHECK.check_sources(self.root)
                path.write_text(original)

    def test_valid_artifacts_and_workflow_staging(self):
        self.build_artifacts()
        CHECK.check_artifacts(self.root)
        result, calls = self.workflow_stage()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("add -u -- releases", calls)  # Record obsolete tracked upgrader removals.
        self.assertIn("releases/Upgrade-My-Kobo-To-v2.6.9.apk", calls)
        self.assertNotIn("push", calls)

    def test_missing_upgrader_fails_before_git_add(self):
        self.build_artifacts()
        (self.root / "releases/Upgrade-My-Kobo-To-v2.6.9.apk").unlink()
        result, calls = self.workflow_stage()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Expected only Upgrade-My-Kobo-To-v2.6.9.apk", result.stderr)
        self.assertNotRegex(calls, r"(?m)^add ")

    def test_obsolete_upgrader_is_rejected(self):
        self.build_artifacts()
        (self.root / "releases/Upgrade-My-Kobo-To-v2.6.0.apk").write_bytes(b"stale")
        with self.assertRaisesRegex(ValueError, "Expected only"):
            CHECK.check_artifacts(self.root)

    def test_missing_or_corrupt_payload_cannot_be_uploaded(self):
        self.build_artifacts()
        path = self.root / "releases/apps/Podcasts.apk"
        path.write_bytes(b"corrupt")
        with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
            CHECK.check_artifacts(self.root)
        path.unlink()
        with self.assertRaisesRegex(ValueError, "Missing or ambiguous"):
            CHECK.check_artifacts(self.root)

    def test_incomplete_or_unsafe_checksum_manifest_is_rejected(self):
        self.build_artifacts()
        path = self.root / "releases/SHA256SUMS.txt"
        original = path.read_text()
        for altered in ("".join(line for line in original.splitlines(True) if not line.endswith("  Podcasts.apk\n")),
                        original + original.splitlines(True)[0],
                        original + "0" * 64 + "  ../outside.apk\n"):
            with self.subTest(altered=altered[-80:]):
                path.write_text(altered)
                with self.assertRaises(ValueError):
                    CHECK.check_artifacts(self.root)


if __name__ == "__main__":
    unittest.main(verbosity=2)
