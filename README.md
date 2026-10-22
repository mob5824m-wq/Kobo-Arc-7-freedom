# Kobo Arc 7 Freedom (`ca.arc7freedom.app` — `v2.1.0`)

![Kobo Arc 7 Freedom Logo Banner](releases/logo-banner.png)

**Full System-Wide Customizable App Bar (`v2.1.0`), Custom Skinned Android 4.2 OS Suite, Reskinned High-DPI System & App Icons, Two-Way Chromebook <-> Kobo Wired USB Internet, Pre-Bundled Official VLC Media Player (`org.videolan.vlc`), Full Calendar + Home Widget, Photos & Photo Filter Studio, Hardware-Tuned HD Camera with Front Screen Flash, Scientific Calculator & Unit Converter, Notes & Color Sketchpad, File & ZIP Explorer, Custom Android 4.2 APK Installer, 20 Internet Archive (`archive.org`) Alarm Chimes, Aurora Store (`v4.8.5`), Verified F-Droid (`v1302`), TLS 1.3 Gateway, `python3` + `apt`, & Anti-Brick Root Suite for Kobo Arc 7 (`T647` / `apollojr`, MT8125) & Kobo Arc 7HD (`T416`, Tegra 3).**

---

## Direct Zero-Redirect `v2.1.0` Downloads (GitHub Releases + Raw CDN — Zero GitHub Pages)

All APKs are `zipalign`ed, **Android 4.2 Dalvik (`DEX 035`) + APK Signature Scheme v1 (JAR) / v2 / v3** signed with permanent release certificate [`keystore/arc7freedom-release.jks`](keystore/arc7freedom-release.jks) (`SHA256: 11:FD:17:ED:80:C9:13:EF:CB:F9:8F:76:73:DA:00:90:42:E7:64:7E:44:E5:D6:C5:F4:2F:CA:A5:CE:4C:D1:0F`):

- **Latest GitHub Release (`v2.1.0`):** **[https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest)**
- **Direct APK & Tool Streams:**
  - **[`Upgrade-My-Kobo-To-v2.1.0.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Upgrade-My-Kobo-To-v2.1.0.apk)** — 1-Tap OTA Root & Full `v2.1.0` Upgrader (`versionCode=210`, installs Freedom `v2.1.0`, Customizable App Bar Launcher `v51`, VLC, Aurora Store, F-Droid `v1302`, `python3`, `apt`, & 120 2026 CAs)
  - **[`KoboArc7Freedom.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/KoboArc7Freedom.apk)** — Main Rooting, Customizable App Bar Studio, Custom Skin, Complete App Suite & Bundled Companion APKs (`v2.1.0`, `versionCode=210`)
  - **[`VLC.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/VLC.apk)** — Standalone Android 4.2 ARMv7 **VLC Media Player** (`org.videolan.vlc`, also pre-bundled inside `KoboArc7Freedom.apk`)
  - **[`AuroraStore.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/AuroraStore.apk)** — Android 4.2 Play/FOSS Store Client with Binary APK Validator & Auto `SHA1withRSA` JAR `v1` Signer (`v4.8.5-arc7`)
  - **[`FDroidClassicLegacy.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/FDroidClassicLegacy.apk)** — Patched F-Droid Classic with Verified Non-Colliding Certificates & TLS 1.3 Bridge (`v1302`)
  - **[`VanillaAndroid4Home.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/VanillaAndroid4Home.apk)** — Custom-Skinned Home Launcher (`v51`) with Fully Customizable Multi-Edge App Bar (`Bottom`/`Top`/`Left`/`Right`/`Hidden`), Custom Top Bar, 5 Skins, Reskinned System Icons, & Long-Press Widgets
- **USB & Home Wi-Fi Companion Scripts:**
  - **Chromebook / Linux Wired USB Internet Bridge:** **[`Start-Chromebook-Wired-Internet.sh`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-Chromebook-Wired-Internet.sh)**
  - **Windows 10 / 11 Home Wi-Fi Rooting Server:** **[`Start-Kobo-WiFi-Server.bat`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-Kobo-WiFi-Server.bat)**
  - **macOS / Linux Home Wi-Fi Rooting Server:** **[`Start-Kobo-WiFi-Server.sh`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-Kobo-WiFi-Server.sh)**

---

## What's New in `v2.1.0` & `v2.0.0`

0. **Full System-Wide Customizable App Bar (`VanillaHomeActivity` + `AppBarConfigStore` + `Arc7Skin`, `v2.1.0`):**
   - **Pin, Reorder, Replace or Remove Any App (0 to 12 Slots):** Long-press any icon on the Home App Bar, long-press any app in the All-Apps Drawer (`Pin to App Bar`), or tap **`App Bar`** on the Home top bar / inside `Kobo Arc 7 Freedom` to add built-in or third-party apps in any order (with smooth horizontal/vertical scroll overflow and 1-tap `Reset Defaults`).
   - **5 Home App Bar Positions & 5 Chrome Styles:** Move the App Bar live between **Bottom Dock**, **Top App Bar**, **Left Side Rail** (ideal for landscape!), **Right Side Rail**, or **Hidden** (swipe up anywhere on Home to open All Apps), with *Floating Glass Pill*, *Full-Width Edge-to-Edge*, *Minimal Transparent*, *Solid Matte Slate*, or *Glowing Neon Accent Rim*, `34/42/50/58 dp` icon sizes, `0%–96%` opacity, label toggle, `:::` placement, and custom Home Top Bar toggles.
   - **Customizable In-App Top App Bars Across Every Built-In App:** Customize header height (`Compact` / `Standard` / `Tall`), chrome style (`OS Gradient` / `Flat Solid` / `Tinted Accent` / `Minimal + Accent Underline`), `Centered` vs `Left` titles, and a built-in **`Apps ▾` Quick App Bar Switcher** right inside every app (or long-press any app header to customize live!).

1. **Two-Way Wired USB Internet with a Chromebook (`Arc7WiredInternetActivity` + `Arc7ChromebookUsbService` + `Start-Chromebook-Wired-Internet.sh`):**
   - **Share Chromebook Internet -> Kobo over USB:** Solves Android `4.2.2` lacking `adb reverse` via a high-speed `ARC7USB1` reverse-multiplexed socket pool over `adb forward tcp:18880 tcp:18880` (`127.0.0.1:18881`), seamlessly powering `Arc7 Browser`, `Aurora Store`, `F-Droid`, `archive.org` chime downloads, `apt`, `python3`, and kernel `iptables` NAT!
   - **Share Kobo Wi-Fi -> Chromebook over USB (`rndis0`):** One-tap plug-and-play USB Ethernet tethering (`192.168.42.129/24` + built-in DHCP/DNS responder + kernel IPv4 NAT) — ChromeOS recognizes Kobo immediately as a wired Ethernet adapter with zero drivers!
   - **USB-OTG Ethernet Adapter Mode (`eth0`):** Auto-configures wired Ethernet dongles via `dhcpcd`.
2. **Full Standalone `Arc7 Calendar` App + Home Widget (`Arc7CalendarActivity` + `Arc7CalendarWidgetProvider`):**
   - Full Month Grid + Agenda view, screen-waking reminder chimes, built-in holidays, color tags, `.ics` import/export, and resizable Home Screen Agenda Widget.
3. **Useful Daily-Driver Apps (`Arc7CalculatorActivity`, `Arc7NotesActivity`, `Arc7FileManagerActivity`):**
   - **Arc7 Calculator:** Scientific keypad (`sin`, `cos`, `tan`, `ln`, `log`, `√`, `^`, `!`, `π`, `e`), clickable History Tape, and Live Unit Converter (`Temperature`, `Length`, `Mass`, `Digital Data`).
   - **Arc7 Notes & Sketchpad:** Two-pane tablet notebook with Markdown notes, interactive tap-to-check checklists, finger/stylus color canvas, and `.md`/`.png` export.
   - **Arc7 Files:** Full Internal + MicroSD + `/` explorer with Copy/Move/Rename, `.zip` create/extract, and 1-tap dispatch to `Arc7 APK Installer`, `VLC`, `Arc7 Photos`, `Arc7 Audio`, & `Arc7 Calendar`.
4. **20 Alarm Chimes Downloaded from the Internet Archive (`archive.org`):**
   - Downloads & SHA-1-verifies 20 real `.ogg` chimes from `https://archive.org/download/...`, caches them locally on `/sdcard/Arc7Freedom/chimes/`, supports live `archive.org` audio search & download right inside `Arc7 Clock`, and preserves per-alarm `0%–100%` volume.
5. **Pre-Bundled VLC Media Player (`org.videolan.vlc`):**
   - Bundles Android 4.2 ARMv7 `VLC.apk` offline and installs it automatically during `v2.0.0` setup/upgrade.
6. **Custom Arc7 OS Skin (`Arc7Skin` + `Arc7LauncherSkin`):**
   - 5 selectable skins (*Obsidian Cyan*, *Midnight Emerald*, *Solaris Amber*, *Amethyst Dusk*, *E-Ink Frost*) across Launcher, dock, dialogs, and built-in apps.
7. **Reskinned Default System & Built-In App Icons (`scripts/generate-icons.py`):**
   - 28 custom high-DPI (`mdpi`/`hdpi`/`xhdpi`/`xxhdpi`) squircle icons automatically applied across every system package (`Settings`, `Chrome`, `Browser`, `Downloads`, `Music`, `Gallery`, `Camera`, `Calculator`, `Calendar`, `Clock`, `Email`, `Contacts`, `Play Store`, `Kobo Books`, `VLC`, and all 18 `Arc7` components).
8. **Custom `Arc7 APK Installer` (`Arc7PackageInstallerActivity`):**
   - Handles `application/vnd.android.package-archive` system-wide with binary manifest/DEX/ABI compatibility inspection, dangerous-permission highlighting, automatic `SHA1withRSA` JAR `v1` signature repair for Android 4.2, and fast root installation.
9. **Standalone `Arc7 Photos` App (`Arc7PhotosActivity`):**
   - Multi-album scanner across Internal Storage & MicroSD, pinch-to-zoom viewer, 7 live color filters, 90° rotate & flip, slideshow, 1-tap Home Wallpaper setter, and VLC video launch.
10. **Standalone `Arc7 Camera` App (`Arc7CameraActivity`):**
    - Explicit `Camera.open(idx)` sensor handling tailored to Kobo Arc 7 HD front camera & Arc 7HD dual sensors, HD Photo + `.mp4` Video recording, **Front Screen Flash**, 3s/10s Self-Timer, 3x3 Grid, & Color FX.
11. **GitHub Pages Completely Removed:**
    - Deleted `docs/`, all GitHub Pages CI steps/jobs/permissions, and all hosted Pages URLs repository-wide.

---

## Reproducible Source Build & Automated End-to-End Verification

```bash
chmod +x scripts/build-all.sh releases/Start-Chromebook-Wired-Internet.sh tools/serve-over-wifi.sh
python3 scripts/generate-icons.py
./scripts/build-all.sh
python3 scripts/verify-apks.py
```
