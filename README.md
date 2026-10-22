# Kobo Arc 7 Freedom (`ca.arc7freedom.app` — `v1.9.1`)

![Kobo Arc 7 Freedom Logo Banner](docs/logo-banner.png)

**Anti-Brick Verified Root, Working Over-The-Air (OTA) Rooting & Installer Website, Fixed Aurora Store Android 4.2 APK Downloader + Auto `SHA1-RSA` JAR `v1` Signer (`v4.8.5`), Fixed F-Droid Classic Repository Certificate Verification (`v1302`), Automatic Local Timezone + Atomic Clock Sync, Native ARMv7 `python3` & `apt` Linux Toolchain, Smooth 60 fps Animations, Clean Long-Press Widget Android 4 Home (`v49`), 12-Hour Time (`AM/PM`), 14 Nostalgic Apple & Android Alarm Chimes, Per-Alarm Volume & Complete Suite for the Kobo Arc 7 (`T647` / `apollojr`, MediaTek MT8125) & Kobo Arc 7HD (`T416`, NVIDIA Tegra 3).**

---

## Working Over-The-Air (OTA) Rooting & Installer Portals (`v1.9.1`)

All APKs are `zipalign`ed, **Android 4.2 Dalvik (`DEX 035`) + APK Signature Scheme v1 (JAR) / v2 / v3** signed with permanent certificate [`keystore/arc7freedom-release.jks`](keystore/arc7freedom-release.jks) (`SHA256: 11:FD:17:ED:80:C9:13:EF:CB:F9:8F:76:73:DA:00:90:42:E7:64:7E:44:E5:D6:C5:F4:2F:CA:A5:CE:4C:D1:0F`) and streamed directly with **zero `302` S3 query redirects**:

- **Cloud Over-The-Air Website (GitHub Pages):** **[https://mob5824m-wq.github.io/Kobo-Arc-7-freedom/](https://mob5824m-wq.github.io/Kobo-Arc-7-freedom/)**
- **Instant Cloud OTA Mirror (Zero-Setup Global CDN):** **[https://htmlpreview.github.io/?https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/blob/main/docs/index.html](https://htmlpreview.github.io/?https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/blob/main/docs/index.html)**
- **Direct Zero-Redirect OTA APK Streams (`200 OK` Fastly CDN):**
  - **[`Upgrade-My-Kobo-To-v1.9.1.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Upgrade-My-Kobo-To-v1.9.1.apk)** — 1-Tap OTA Root & Full `v1.9.1` Upgrader (`versionCode=191`)
  - **[`KoboArc7Freedom.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/KoboArc7Freedom.apk)** — Main Rooting, Suite, Auto Timezone, `python3` & `apt` APK (`v1.9.1`, `versionCode=191`)
  - **[`AuroraStore.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/AuroraStore.apk)** — Android 4.2 Play/FOSS Store Client with Binary APK Validator & Auto `SHA1-RSA` JAR `v1` Signer (`com.aurora.store`, `v4.8.5-arc7`, `versionCode=485`)
  - **[`FDroidClassicLegacy.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/FDroidClassicLegacy.apk)** — Patched F-Droid Classic with Verified Non-Colliding Repository Certificates, `SHA-256` Index Verification & TLS 1.3 Bridge (`eu.bubu1.fdroidclassic.debug`, `versionCode=1302`)
  - **[`VanillaAndroid4Home.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/VanillaAndroid4Home.apk)** — Clean Stock Android 4.2 Home Launcher (`versionCode=49`, Long-Press Widgets, zero `+ Widget` clutter)
- **Home Wi-Fi Zero-SSL OTA Rooting Servers (For Brand-New Unrooted Factory Kobo Tablets):**
  - **Windows 10 / 11 (Zero Python Needed):** **[`Start-Kobo-WiFi-Server.bat`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-Kobo-WiFi-Server.bat)** — Concurrent `.NET` `RunspacePool` multi-socket server with HTTP `Range: bytes=` (`206 Partial Content`)
  - **macOS / Linux:** **[`Start-Kobo-WiFi-Server.sh`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-Kobo-WiFi-Server.sh)** — Concurrent `ThreadingHTTPServer` with `Range` + `/raw/` direct streams

---

## Hardware Fixes Shipped in `v1.9.1`

1. **Fixed Aurora Store APK `"Parse error: There is a problem parsing the package"` on Android 4.2.2 (`AuroraStore` `v4.8.5-arc7` + `Arc7ApkKit`):**
   - Queries **F-Droid Archive + F-Droid Main `index-v1.jar`** (`minSdkVersion <= 17`, `ARMv7`/universal) and verified Android 4.2 releases instead of returning `minSdkVersion >= 21` / `DEX 038/039` / `arm64-only` APKs from IzzyOnDroid.
   - Binary-inspects every downloaded APK's compiled `AndroidManifest.xml`, `classes*.dex`, and `lib/` ABIs before installation, automatically adds a 4-byte-aligned **`SHA1-Digest` + `SHA1withRSA` JAR `v1` signature** when needed (resolving AOSP Bug `#38321` across Aurora Store, F-Droid Classic, Arc7 Browser, and Wi-Fi File Drive), and installs via `/data/local/tmp/` `pm install -r` with widened `8 KB` URL buffers and unwrapped `127.0.0.1:8118` proxy URLs in `libarc7tls.so`.
2. **Fixed F-Droid `"Signing certificate does not match!"` (`f-droid certificates invalid`) Error (`FDroidClassicLegacy` `v1302`):**
   - Resolved the exact `resources.arsc` `default_repos` URL collision where `str[462]` (`F-Droid Classic`) overwrote `f-droid.org/repo`'s `CN=Ciaran Gultnieks` (`43238D51...`) certificate with `CN=marcus-tux` (`5187CFD9...`) in SQLite on every first launch, restored genuine **PKCS#7 `SHA256withRSA` `index-v1.jar` verification** on Android 4.2, persisted `/data/local/arc7_tls_ca/` across app upgrades, and auto-cleared stale `< 1302` SQLite state.

---

## All 12 Major Upgrades Shipped in `v1.9.0`

1. **Automatic Local Timezone Detection + Bug-Free Atomic Time Sync:**
   - Queries multiple independent geolocation timezone endpoints (`ipwho.is`, `ip-api.com`, `timeapi.io`, `worldtimeapi.org`) over Wi-Fi to detect your real IANA timezone (`e.g. America/Toronto`), applies `AlarmManager.setTimeZone` + `setprop persist.sys.timezone`, AND fixes the UTC-vs-local `date -s` offset bug so kernel UTC epoch (`settimeofday`) and local wall time are exact to the second automatically.
2. **Fixed Over-The-Air (OTA) Website & Local Wi-Fi Servers:**
   - Added automated GitHub Pages deployment (`actions/configure-pages@v5` + `actions/deploy-pages@v4`), switched all static web portal download buttons to direct zero-`302` binary CDN streams, AND upgraded `Start-Kobo-WiFi-Server.bat` / `.sh` from single-socket blocking loops to true concurrent thread-pooled HTTP/1.1 + `206 Range` servers.
3. **Automatic Stock Default Launcher Removal:**
   - Safely archives & removes stock `Launcher2.apk` (`com.android.launcher`, `com.kobo.home`, `com.kobo.launcher`, `com.kobo.tapestry`) into `/data/local/arc7_kobo_archive/` after verifying `VanillaAndroid4Home` (`ca.arc7freedom.launcher`) is active.
4. **Bundled & Auto-Installed Aurora Store (`com.aurora.store`):**
   - Native Android 4.2 (`minSdk=15`, `DEX 035`, `TLS 1.3`) Google Play Store client pre-bundled inside `KoboArc7Freedom.apk` and installed automatically alongside F-Droid Classic.
5. **Hardware-Accelerated 60 fps Animations Across All Custom Apps:**
   - Shared `Arc7UiAnimator` engine adds smooth slide/fade activity transitions, directional tab/page transitions, `LayoutTransition` container animations, and spring touch feedback across all 10 Activities in `KoboArc7Freedom`, `VanillaAndroid4Home`, and `AuroraStore`.
6. **Removed `Kobo Care` & Discontinued Preloads:**
   - Automatically archives & removes `Kobo Care` (`KoboCare*`, `com.kobo.care/support/help/diagnostics/analytics`) plus dead/shut-down preloads (`Pocket`, `Zinio`, `7digital`, `Rdio`, `Google+`, `Talk`/`Hangouts`, `Currents`/`Magazines`, `GoogleEars`, `Videos`, `GenieWidget`) while preserving all core Android 4.2 & hardware HAL packages.
7. **Long-Press Home Screen to Add Widgets (`VanillaAndroid4Home` v49):**
   - Long-press anywhere on the Home Screen workspace background to open the Widget Picker.
8. **Removed `+ Widget` Button & `"Tap + to add widgets"` Text:**
   - Completely removed both the top-bar `+ Widget` button and all `"Tap + Widget"` text/placeholders from the launcher.
9. **Full 12-Hour Time (`AM/PM`) Support:**
   - Clean 12-hour (`AM/PM`) & 24-hour support across Clock, Alarms, World Clocks, Nightstand Dock, Fullscreen Wake Alert, Home Screen Clock Widget, Home Launcher Clock, and Android system `TIME_12_24`.
10. **14 Nostalgic Apple & Nostalgic Android Alarm Chimes (With Live Preview):**
    - Synthesizes authentic harmonic Apple chimes (*Marimba*, *Radar*, *Opening*, *Xylophone*, *Harp*, *Chimes*, *Sci-Fi*) and nostalgic Android chimes (*Helium*, *Oxygen*, *Pyxis*, *Cesium*, *Argon*, *Platinum*, plus *Arc7 Sunrise*) in 44.1 kHz PCM with instant live preview right inside the chime picker.
11. **Individual Per-Alarm Volume (`0%–100%`):**
    - Every individual alarm saves and rings at its own dedicated `0%–100%` volume level.
12. **Automatic Native `python3` & `apt` Installation:**
    - Bundles static ARMv7 `python3` (`libarc7py3.so`) and TLS-1.3 `apt` / `apt-get` / `apt-cache` (`libarc7apt.so`), installs them into `/system/xbin/` + `Arc7 Terminal` `PATH` immediately, AND runs an automatic background `apt update && apt install -y python3 py3-pip curl nano ca-certificates` into `/data/local/arc7linux/`.

---

## Reproducible Source Build & Automated End-to-End Verification

```bash
chmod +x scripts/build-all.sh tools/serve-over-wifi.sh
python3 scripts/generate-icons.py
./scripts/build-all.sh
python3 scripts/verify-apks.py
```
