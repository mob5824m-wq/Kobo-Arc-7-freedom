# Kobo Arc 7 Freedom (`ca.arc7freedom.app` — `v2.2.0`)

![Kobo Arc 7 Freedom Logo Banner](releases/logo-banner.png)

**Complete Daily-Driver Android 4.2 OS & App Ecosystem (`v2.2.0`, `versionCode=220` + Home Launcher `v52`): 4-Source Free E-Book Storefront (`VK Books` [`vk.com`], `Project Gutenberg`, `Open Library`, `Standard Ebooks`) with `.fb2`/`.fb2.zip`/`.pdf`/`.epub`/`.cbz` Support, Native Offline PDF Reader & Camera Document Scanner, Live `Open-Meteo` Weather + Home Widget, 30,000+ World Radio + Podcast `.mp3` Downloader + Offline RSS Reader, 44.1 kHz Studio Voice Recorder & Lossless WAV Trimmer, Zero-Ad Offline Arcade (`Chess vs AI`, `2048`, `Sudoku`, `Minesweeper`, `Solitaire`), System-Wide `/etc/hosts` AdBlocker & Root Firewall (`Arc7 Shield`), `+384 MB` Kernel `zRAM`/Swap & CPU Profiles (`Arc7 Boost`), `1280×800` USB/Wi-Fi Second Screen + Macro Deck, Landscape Dual-Pane Split View, Wireless WebDAV Drive (`:8085`) & LAN Share -> `VLC` Streaming, Offline Dictionary & Clean `Wikipedia`, Multi-Page App Folders / Grid Density / PIN App Lock (`Launcher v52`), System-Wide Customizable App Bar, 5 OS Skins + 37 High-DPI Icons, Two-Way Chromebook Wired USB Internet, Pre-Bundled `VLC.apk`, `Aurora Store v4.8.5`, Verified `F-Droid v1302`, TLS 1.3 Gateway, `python3` + `apt`, & Anti-Brick Root Suite.**

---

## Direct Zero-Redirect `v2.2.0` Downloads (GitHub Releases + Raw CDN)

All APKs are `zipalign`ed, **Android 4.2 Dalvik (`DEX 035`) + APK Signature Scheme v1 (JAR) / v2 / v3** signed with permanent release certificate [`keystore/arc7freedom-release.jks`](keystore/arc7freedom-release.jks) (`SHA256: 11:FD:17:ED:80:C9:13:EF:CB:F9:8F:76:73:DA:00:90:42:E7:64:7E:44:E5:D6:C5:F4:2F:CA:A5:CE:4C:D1:0F`):

- **Latest GitHub Release (`v2.2.0`):** **[https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest)**
- **Direct APK & Tool Streams:**
  - **[`Upgrade-My-Kobo-To-v2.2.0.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Upgrade-My-Kobo-To-v2.2.0.apk)** — 1-Tap OTA Root & Full `v2.2.0` Upgrader (`versionCode=220`, installs Freedom `v2.2.0`, Multi-Page & Folder Launcher `v52`, VLC, Aurora Store, F-Droid `v1302`, `python3`, `apt`, & 120 2026 CAs)
  - **[`KoboArc7Freedom.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/KoboArc7Freedom.apk)** — Main Rooting, 28-App Daily-Driver Suite, Customizable App Bar Studio, Custom Skin & Bundled Companion APKs (`v2.2.0`, `versionCode=220`)
  - **[`VLC.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/VLC.apk)** — Standalone Android 4.2 ARMv7 **VLC Media Player** (`org.videolan.vlc`, also pre-bundled inside `KoboArc7Freedom.apk`)
  - **[`AuroraStore.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/AuroraStore.apk)** — Android 4.2 Play/FOSS Store Client with Binary APK Validator & Auto `SHA1withRSA` JAR `v1` Signer (`v4.8.5-arc7`)
  - **[`FDroidClassicLegacy.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/FDroidClassicLegacy.apk)** — Patched F-Droid Classic with Verified Non-Colliding Certificates & TLS 1.3 Bridge (`v1302`)
  - **[`VanillaAndroid4Home.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/VanillaAndroid4Home.apk)** — Custom-Skinned Home Launcher (`v52`) with Multi-Page Desktops (`3–5`), Desktop & App Bar Folders, Grid Density (`4–7` cols), 4-Digit App PIN Lock, JSON Layout Backup/Restore, Multi-Edge App Bar, 5 Skins, 37 High-DPI Icons, & Long-Press Widgets
- **USB & Home Wi-Fi Companion Scripts:**
  - **Chromebook / PC / Mac Extended `1280×800` Second Screen & Macro Deck Sender:** **[`Start-Kobo-Second-Screen.py`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-Kobo-Second-Screen.py)**
  - **Chromebook / Linux Wired USB Internet Bridge:** **[`Start-Chromebook-Wired-Internet.sh`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-Chromebook-Wired-Internet.sh)**
  - **Windows 10 / 11 Home Wi-Fi Rooting Server:** **[`Start-Kobo-WiFi-Server.bat`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-Kobo-WiFi-Server.sh)**
  - **macOS / Linux Home Wi-Fi Rooting Server:** **[`Start-Kobo-WiFi-Server.sh`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-Kobo-WiFi-Server.sh)**

---

## What's New in `v2.2.0`

1. **4-Source Free E-Book Storefront + Native `.FB2` / `.FB2.ZIP` / `.PDF` / `.EPUB` / `.CBZ` Reader (`Arc7 E-Reader`):**
   - Built-in **`VK Books` (`vk.com`)** search & 1-tap download (`api.vk.com/method/docs.search` + OAuth login/token + zero-login public `vk.com` search/extractor + live `m.vk.com` TLS 1.3 book interceptor), **`Project Gutenberg` (`70,000+` books)**, **`Open Library`**, and **`Standard Ebooks`**, plus native **`.fb2` / `.fb2.zip`** XML + cover/image parser, bookmarks, reading progress `%`, and 4 reading themes (*Sepia*, *Daylight*, *AMOLED Dark*, *Forest Sage*).
2. **Native Offline PDF Reader & Multi-Page Camera Document Scanner (`Arc7 PDF & Scanner`):**
   - Read `.pdf` textbooks/manuals offline OR scan multi-page documents straight from the Kobo camera to `%PDF-1.4` files in `/sdcard/Documents/Scans/`.
3. **Live `Open-Meteo` Weather App + Home Screen Weather Widget (`Arc7 Weather`):**
   - Zero-API-key TLS 1.3 city search, current conditions, 24-hour strip, 7-day forecast, °C/°F toggle, offline cache, and Home Screen AppWidget.
4. **30,000+ Live World Internet Radio, Podcast `.mp3` Downloader & Offline RSS Reader (`Arc7 Radio & Feeds`):**
   - Screen-off TLS-relayed world radio streaming, Podcast search & offline `.mp3` downloads, and RSS 2.0 / Atom offline feed reader.
5. **Studio 44.1 kHz Waveform Voice Recorder & Lossless WAV Trimmer (`Arc7 Recorder`):**
   - 16-bit PCM `.wav` recording with Android 4.2 Pause/Resume, live 60 fps waveform, and lossless `.wav` sample trimming.
6. **Zero-Ad 60 fps Offline Classic Games Suite (`Arc7 Arcade`):**
   - **Chess vs 2-Ply AI**, **2048**, **Sudoku**, **Minesweeper**, and **Klondike Solitaire**.
7. **System-Wide `/etc/hosts` Ad/Tracker Blocker & Per-App Root Firewall (`Arc7 Shield`):**
   - Pure `mksh`/`toolbox` hosts adblocker + live `StevenBlack/hosts` sync over TLS 1.3 + per-app UID `iptables` firewall.
8. **`+384 MB` Kernel `zRAM`/Swap Expander, Quad-Core CPU Profiles & Battery Doctor (`Arc7 Boost`).**
9. **Chromebook / PC / Mac Extended `1280×800` USB/Wi-Fi Second Screen + 12-Key Macro Deck (`Arc7 Second Screen` + `Start-Kobo-Second-Screen.py`).**
10. **Landscape Dual-Pane Split-Screen Multitasking (`Arc7 Split View` — Web, Books/PDF, Notes, Dict/Wiki & Calculator side-by-side).**
11. **Upgraded `VanillaAndroid4Home v52` (`3–5` Multi-Page Desktops, `4–7` Column Grid Density, Desktop & App Bar `@folder:` App Folders, 4-Digit App PIN Lock, and JSON Layout Backup/Restore).**
12. **Wireless WebDAV + HTML5 Network Drive (`:8085`) & Remote LAN Share Browser with Direct `VLC` Streaming (`Arc7 Files`).**
13. **Offline English Lexicon + `Wiktionary` & Clean Reader `Wikipedia` with Offline Saved Articles (`Arc7 Dict & Wiki`).**
14. **Automatic Sunset-to-Sunrise Night Light (`20:00–07:00`), Auto-Charging Smart Bedside Dock & Smooth 30s Fade-Out Audio Sleep Timer.**
