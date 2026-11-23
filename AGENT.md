# AGENT.md — Kobo Arc 7 Freedom

Instructions for AI coding agents working in this repository.

## What this repo is

**Kobo Arc 7 Freedom** — a root/customization suite for the Kobo Arc 7
(Android 4.2.2 tablet). Current release: **v2.7.0 / versionCode 270**.
The main suite APK is `releases/KoboArc7Freedom.apk`; the data-preserving
in-place upgrader is `releases/Upgrade-My-Kobo-To-v2.7.0.apk`. There are
also 32 separately installable default apps (`releases/apps/*.apk`),
YouTube / YouTube Music, VLC, Aurora Store, F-Droid Classic, and a
Vanilla Android 4 home launcher.

## Non-negotiable product rules

- **In-place updates only.** Never add uninstall, data wipe, forced
  downgrade, or automatic rooting fallback. Failed/signature-conflicting
  updates must stop safely, not silently proceed.
- **Version consistency.** Suite, upgrader, YouTube/Music and all default
  apps declare 2.7.0 / 270 in the actual Android manifests — not just
  labels. Any version bump must update manifests, Gradle, workflow and
  updater paths together.
- **Permanent signer.** All APKs are signed with the same permanent
  certificate (v1 + v2 + v3). Never change signing identity.
- **Offline-first.** The Kobo may have no internet. Everything needed to
  install/repair must be bundled in the APKs (offline assets, embedded
  catalog, QR transfer). No runtime downloads required for core flows.
- **Checksums are authoritative.** `releases/SHA256SUMS.txt` (53 entries)
  must match the actual files. Mixed-build artifacts are refused, never
  silently served.

## Build & verify

```bash
make apks        # TOOLCHAIN_DIR=build/toolchain ./scripts/build-all.sh
make verify      # scripts/verify-apks.py — ALL gates must pass 100%
python3 scripts/check-release-version.py --artifacts
python3 scripts/build-windows-tools.py --check
