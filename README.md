# Kobo Arc 7 Freedom (`ca.arc7freedom.app` — `v2.3.0`)

![Kobo Arc 7 Freedom Logo Banner](releases/logo-banner.png)

**Complete Daily-Driver Android 4.2 OS & App Ecosystem (`v2.3.0`, `versionCode=230` + Home Launcher `v53`): Strict School Wi-Fi Rescue (`wpa_cli` Hidden SSID + `PEAP`/`TTLS`/`eduroam` WPA2-Enterprise + Ch. `12–13` Unlock + MAC Randomizer + Captive Portal Login + Port-443 DoH), Native System-Wide Android 4.2 VPNs & Official Tailscale (`Arc7VpnService` `/dev/tun` + `libarc7vpn.so` + WireGuard `.conf` + OpenVPN `.ovpn` + Port-443 TLS 1.3 Stealth VPN + `iptables` Kill Switch), Full `1280×800` PC Stream Deck Pro (`OBS WebSocket v5` + `15`/`24`/`32`/`6` Key Grids + Touch Volume Faders + Live PC Web Studio over Wi-Fi / USB ADB / Tailscale), Touch Drag-and-Drop Rearrangeable Home Launcher (`v53`), 4-Source Free E-Book Storefront (`VK Books` [`vk.com`], `Project Gutenberg`, `Open Library`, `Standard Ebooks`) with `.fb2`/`.fb2.zip`/`.pdf`/`.epub`/`.cbz` Support, Native Offline PDF Reader & Camera Document Scanner, Live `Open-Meteo` Weather + Home Widget, 30,000+ World Radio + Podcast `.mp3` Downloader + Offline RSS Reader, 44.1 kHz Studio Voice Recorder, Zero-Ad Offline Arcade, `Arc7 Shield` AdBlocker, `+384 MB` `zRAM` Boost, `1280×800` Extended Second Screen, Split View, Wireless WebDAV Drive (`:8085`), 5 OS Skins + 39 High-DPI Icons, Two-Way Chromebook Wired USB Internet, Pre-Bundled `VLC.apk`, `Aurora Store v4.8.5`, Verified `F-Droid v1302`, TLS 1.3 Gateway, `python3` + `apt`, & Anti-Brick Root Suite.**

---

## Direct Zero-Redirect `v2.3.0` Downloads (GitHub Releases + Raw CDN)

All APKs are `zipalign`ed, **Android 4.2 Dalvik (`DEX 035`) + APK Signature Scheme v1 (JAR) / v2 / v3** signed with permanent release certificate [`keystore/arc7freedom-release.jks`](keystore/arc7freedom-release.jks) (`SHA256: 11:FD:17:ED:80:C9:13:EF:CB:F9:8F:76:73:DA:00:90:42:E7:64:7E:44:E5:D6:C5:F4:2F:CA:A5:CE:4C:D1:0F`):

- **Latest GitHub Release (`v2.3.0`):** **[https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest)**
- **Direct APK & Tool Streams:**
  - **[`Upgrade-My-Kobo-To-v2.3.0.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Upgrade-My-Kobo-To-v2.3.0.apk)** — 1-Tap OTA Root & Full `v2.3.0` Upgrader (`versionCode=230`, installs Freedom `v2.3.0`, Drag-and-Drop Launcher `v53`, School Wi-Fi & Tailscale VPN, PC Stream Deck, VLC, Aurora Store, F-Droid `v1302`, `python3`, `apt`, & 120 2026 CAs)
  - **[`KoboArc7Freedom.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/KoboArc7Freedom.apk)** — Main Rooting, 30-App Daily-Driver Suite, School Wi-Fi & Tailscale VPN, PC Stream Deck, Customizable App Bar Studio, Custom Skin & Bundled Companion APKs (`v2.3.0`, `versionCode=230`)
  - **[`VLC.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/VLC.apk)** — Standalone Android 4.2 ARMv7 **VLC Media Player** (`org.videolan.vlc`, also pre-bundled inside `KoboArc7Freedom.apk`)
  - **[`AuroraStore.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/AuroraStore.apk)** — Android 4.2 Play/FOSS Store Client with Binary APK Validator & Auto `SHA1withRSA` JAR `v1` Signer (`v4.8.5-arc7`)
  - **[`FDroidClassicLegacy.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/FDroidClassicLegacy.apk)** — Patched F-Droid Classic with Verified Non-Colliding Certificates & TLS 1.3 Bridge (`v1302`)
  - **[`VanillaAndroid4Home.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/VanillaAndroid4Home.apk)** — Custom-Skinned Home Launcher (`v53`) with Touch Drag-and-Drop Free-Slot Icon Placement, Folder Drop/Creation, Multi-Page Desktops (`3–5`), Grid Density (`4–7` cols), 4-Digit App PIN Lock, Multi-Edge App Bar, 5 Skins, 39 High-DPI Icons, & Long-Press Widgets
- **PC Stream Deck, School VPN Relay, USB & Home Wi-Fi Companion Scripts:**
  - **Windows / macOS / Linux PC Stream Deck Server + Live Web Studio (`:18991` / `:18995`):** **[`Start-PC-Stream-Deck.py`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-PC-Stream-Deck.py)** | **[`Start-PC-Stream-Deck.bat`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-PC-Stream-Deck.bat)** | **[`Start-PC-Stream-Deck.sh`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-PC-Stream-Deck.sh)**
  - **Personal Port-443 TLS 1.3 Stealth School VPN Home Relay:** **[`Start-PC-School-VPN-Relay.py`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-PC-School-VPN-Relay.py)**
  - **Chromebook / PC / Mac Extended `1280×800` Second Screen & Macro Deck Sender:** **[`Start-Kobo-Second-Screen.py`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-Kobo-Second-Screen.py)**
  - **Chromebook / Linux Wired USB Internet Bridge:** **[`Start-Chromebook-Wired-Internet.sh`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-Chromebook-Wired-Internet.sh)**
  - **Windows 10 / 11 Home Wi-Fi Rooting Server:** **[`Start-Kobo-WiFi-Server.bat`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-Kobo-WiFi-Server.bat)**
  - **macOS / Linux Home Wi-Fi Rooting Server:** **[`Start-Kobo-WiFi-Server.sh`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-Kobo-WiFi-Server.sh)**

---

## What's New in `v2.3.0`

1. **Strict School Wi-Fi Rescue & Network Policy Bypass (`Arc7 Wi-Fi & VPN`):**
   - Fixes strict school Wi-Fi not showing up or refusing to connect on the Kobo Arc 7's 2.4 GHz `MT6628` radio + Android `4.2.2` stack via raw `WifiManager` + root `wpa_cli` air scan diagnostics (detecting Hidden SSIDs, `802.1X` WPA2-Enterprise / `eduroam` APs, Channel `12–13` regulatory lockouts, WPA3-Transition APs, and 5 GHz-only APs), 1-tap `PEAP`/`TTLS`/`MSCHAPv2`/`eduroam` + Hidden SSID configurator, Wi-Fi MAC Randomizer, TLS 1.3 Captive Portal Login Browser, School HTTP Proxy configurator, Port-443 Encrypted DNS-over-HTTPS (`DoH`), and Chromebook USB / 2.4 GHz Hotspot rescue!
2. **Touch Drag-and-Drop App Icon Rearrangement (`VanillaAndroid4Home v53`):**
   - Long-press & drag (or tap **`Rearrange`** on the Home Top Bar) to move any app icon freely into **any grid slot on any desktop page**, **swap positions**, **drop onto another icon to create/add to a Folder**, **drag across multi-page edges**, **drop onto the Top Action Bar** (*Unpin / Rename / Folder / App Info / Uninstall*), **swap App Bar slots**, and **custom-sort the All Apps drawer**!
3. **Full `1280×800` LCD Stream Deck for Windows / macOS / Linux (`Arc7 Stream Deck` + `Start-PC-Stream-Deck.{py,bat,sh}`):**
   - `15` (`5×3`), `24` (`6×4`), `32` (`8×4` XL), and `6` (`3×2` Mega) glowing LCD key layouts over **Wi-Fi LAN**, **zero-lag USB (`adb forward`)**, or **Tailscale IP**, featuring **OBS WebSocket v5 (`:4455`)** scene/record/stream/mute controls, **Media & Touch Volume Faders**, **Momentary Push-to-Talk**, **Live PC CPU / RAM / Volume telemetry**, and **Live PC Browser Deck Studio (`:18995`)**!
4. **System-Wide Android 4.2 VPNs & Official Tailscale (`Arc7VpnService` + `libarc7vpn.so` + `TailscaleManager`):**
   - Full `/dev/tun` `android.net.VpnService` + static ARMv7 `libarc7vpn.so` tunneling every app on the tablet across **Official Upstream Tailscale** (`tailscaled` + `tailscale` ARMv7 mesh + DERP TCP `443` + QR Login + Exit Nodes + `100.64.0.0/10` MagicDNS), **WireGuard (`.conf` + on-device Curve25519 keygen)**, **OpenVPN (`.ovpn`)**, **SOCKS5 / HTTP CONNECT / Port-443 TLS 1.3 Stealth VPN**, **Encrypted DoH**, and **Root `iptables` Kill Switch**!

---

## Also Included from `v2.2.0`–`v1.7.0`

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
