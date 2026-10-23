# Arc7 Freedom (`v2.5.0` — playable YouTube / YouTube Music + truly separate apps)

![Kobo Arc 7 Freedom Logo Banner](releases/logo-banner.png)

**Kobo Arc 7 / Arc 7HD (Android 4.0.3–4.2.2) daily-driver ecosystem, now at `v2.5.0`
(`versionCode=250`).**

`v2.5.0` fixes the two things that were still broken on real hardware:

1. **YouTube & YouTube Music actually play now.** Since 2026 YouTube answers every working
   anonymous InnerTube client with an **empty `formats` array** — only video-only H.264 and
   audio-only AAC **DASH** tracks (`adaptiveFormats` + `initRange`/`indexRange`) and HLS are
   returned. The old code searched for a progressive muxed MP4, found nothing, handed
   `VideoView` an empty URL and died with `MediaPlayer error (1, -1004)` — metadata loaded,
   playback never started.
   * New on-device **ISO-BMFF remuxer** (`MorpheRemuxer`) merges the video-only and audio-only
     DASH tracks into **one fragmented MP4**: two `trak`s, interleaved `moof`/`mdat`, rewritten
     `tkhd` / `tfhd` / `trex` / `mfhd` / `base_data_offset` / `trun` offsets.
   * `MorpheStreamServer` serves it from `127.0.0.1` with an **exact `Content-Length`** and full
     `Range: bytes=…` → `206 Partial Content` support, so Stagefright starts instantly and can seek.
   * Correct **2026 client cascade**: `visionOS 1.04` → `TVHTML5_SIMPLY 1.1` → `Android VR 1.64`
     → `Android Music 7.12` → `Android Creator 26.10`, each with its **own**
     `/youtubei/v1/visitor_id` visitor id (sharing one id across clients raised the bot score and
     403'd every stream) and the `X-Goog-Api-Format-Version: 2` header.
   * Audio-only playback (YouTube Music background / screen-off) spools the AAC `mp4a` DASH track
     into a playable fragmented MP4 — no video downloaded, ~90 % less bandwidth.
2. **Custom apps are genuinely separate applications.** `YouTube` and `YouTube Music` are now
   their **own installable APKs with their own package ids** — `ca.arc7freedom.youtube`
   (`releases/YouTube.apk`) and `ca.arc7freedom.ytmusic` (`releases/YouTubeMusic.apk`). Each has
   its own launcher icon/label, task stack, data directory and background services, appears
   separately under *Settings → Apps*, can be updated or uninstalled on its own, and cross-launches
   the other app. The suite no longer shows duplicate YouTube / YouTube Music icons; the 1-Tap
   Upgrader and the silent companion updater install and refresh both standalone APKs.

Everything from `v2.4.1` and earlier is still included: Launcher `v55` with effortless widget
removal, universal Now Playing notification + Clock Standby, Official Tailscale Google sign-in,
patched F-Droid `v1303`, bundled VLC, Aurora Store, `python3` + `apt`, 120 pre-downloaded 2026
Root CAs, the zero-SSL local Wi-Fi installer portal and the full app suite.

---

## Direct Zero-Redirect `v2.5.0` Downloads (GitHub Releases + Raw CDN)

All APKs are `zipalign`ed, **Android 4.2 Dalvik (`DEX 035`) + APK Signature Scheme v1 (JAR) / v2 / v3**
signed with the permanent release certificate
[`keystore/arc7freedom-release.jks`](keystore/arc7freedom-release.jks)
(`SHA256: 11:FD:17:ED:80:C9:13:EF:CB:F9:8F:76:73:DA:00:90:42:E7:64:7E:44:E5:D6:C5:F4:2F:CA:A5:CE:4C:D1:0F`):

- **Latest GitHub Release (`v2.5.0`):** **[https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest)**
- **Direct APK & Tool Streams:**
  - **[`Upgrade-My-Kobo-To-v2.5.0.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Upgrade-My-Kobo-To-v2.5.0.apk)** — 1-Tap OTA Root & Full `v2.5.0` Upgrader (`versionCode=250`; installs `Arc7 Freedom` `v2.5.0`, the separate `YouTube` & `YouTube Music` apps, Launcher `v55`, F-Droid `v1303`, Tailscale Google Auth, PC Stream Deck, VLC, Aurora Store, `python3`, `apt` & 120 2026 CAs)
  - **[`KoboArc7Freedom.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/KoboArc7Freedom.apk)** — Main Rooting & Daily-Driver Suite (`Arc7 Freedom` `v2.5.0`, `versionCode=250`)
  - **[`YouTube.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/YouTube.apk)** — **Separate** `YouTube` app (`ca.arc7freedom.youtube`, `versionCode=250`): 2026 DASH remux playback, SponsorBlock, Return YouTube Dislike, comments, background audio & offline downloads
  - **[`YouTubeMusic.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/YouTubeMusic.apk)** — **Separate** `YouTube Music` app (`ca.arc7freedom.ytmusic`, `versionCode=250`): AMOLED player, screen-off AAC background playback, synced lyrics, queue/shuffle/repeat, equalizer & offline `.m4a`
  - **[`VLC.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/VLC.apk)** — Standalone Android 4.2 ARMv7 **VLC Media Player** (`org.videolan.vlc`, also pre-bundled inside `KoboArc7Freedom.apk`)
  - **[`AuroraStore.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/AuroraStore.apk)** — Android 4.2 Play/FOSS store client with binary APK validator & auto `SHA1withRSA` JAR `v1` signer (`v4.8.5`)
  - **[`FDroidClassicLegacy.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/FDroidClassicLegacy.apk)** — Patched F-Droid Classic (`v1303`) with the complete Android 4.2 `SHA-256` `Incorrect signature` fix, BER/DER PKCS#7 verifier, non-colliding certs & TLS 1.3 bridge
  - **[`VanillaAndroid4Home.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/VanillaAndroid4Home.apk)** — Custom-skinned **Home** launcher (`v55`) with effortless widget removal, drag-and-drop icons, multi-page desktops, multi-edge app bar, 5 skins & 44 high-DPI icons

---

## What's New in `v2.5.0`

1. **`YouTube` — playable 2026 streams (`MorpheRemuxer` + `MorpheStreamServer` + `MorpheEngine`):**
   - Empty-`formats` DASH handling, video-only `avc1` + audio-only `mp4a` merge into one seekable
     fragmented MP4, exact-length local `206` server, H.264-only codec filter for the MT8125
     decoder, 1080p/720p/480p/360p/240p/144p quality picker that re-muxes on the fly, plus the
     existing SponsorBlock auto-skip, Return YouTube Dislike, comments, background audio and
     offline `.mp4` downloads.
2. **`YouTube Music` — real background audio:** audio-only DASH spool (no video download),
   AMOLED player, synced LRC lyrics, queue/shuffle/repeat, 5-band equalizer + bass boost, sleep
   timer, liked songs and offline `.m4a` downloads.
3. **Truly separate apps (`split-apps/`):**
   - New reusable `build_standalone_app()` build step produces signed `YouTube.apk`
     (`ca.arc7freedom.youtube`) and `YouTubeMusic.apk` (`ca.arc7freedom.ytmusic`) from their own
     manifests, with their own icons, labels, tasks, services and data directories.
   - The suite keeps only compatibility/deep-link entries (no duplicate launcher icons); the
     launcher, 1-Tap Upgrader and silent updater all prefer the standalone packages with a
     graceful in-suite fallback.
4. **Verification:** new `verify_v250_streaming_and_standalone_apps()` gate (package ids, labels,
   launcher filters, v1+v2+v3 signatures, embedded CA bundle + TLS engine, 2026 InnerTube strings,
   upgrader nesting) and a host-JVM suite `scripts/test-morphe-remux.py` that proves the remuxer
   and the strict Range/`206` server byte-for-byte.

---

## What's New in `v2.4.0`

1. **Fixed `YouTube` App — Morphe Edition (`YouTubeActivity` + `MorpheEngine`):**
   - Full standalone **YouTube** launcher app with working Oct 2026 non-SABR InnerTube cascade (`ANDROID_VR 1.64.34`, `VISIONOS 1.03`, `ANDROID_XR 1.69.27`, `ANDROID_MUSIC 7.12.52`, `IOS 21.26.4` + Piped/Invidious fallback), local `http://127.0.0.1:18977` `206 Partial Content` `Range: bytes=...` TLS 1.3 stream proxy (eliminating Android `4.2.2` Stagefright `MediaPlayer error (1, -1004)` and `googlevideo.com` throttling), ad-free hardware H.264/AAC playback (`360p`/`720p` + Audio-Only Screen-Off mode), **SponsorBlock** auto-skip, **Return YouTube Dislike** counts, channels, comments, and offline `.mp4`/`.m4a` downloads.
2. **New Standalone `YouTube Music` Morphe App (`YouTubeMusicActivity`):**
   - Dedicated launcher app with AMOLED dark UI, song/album/artist/playlist search & quick picks, `128–160 kbps` M4A screen-off background playback (`PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_HIGH_PERF`), SponsorBlock non-music skip, synced LRC lyrics, queue/shuffle/repeat, 5-band `Equalizer` + `BassBoost`, and offline `.m4a` downloads.
3. **Effortless Home Screen Widget Removal (`VanillaAndroid4Home v54`):**
   - Custom `InterceptingWidgetHostView.onInterceptTouchEvent` intercepts `420ms` long-presses before clickable child `RemoteViews` swallow them, **`Rearrange`** mode highlights every widget with 1-tap **`✕ Remove`** + **`↕ Size`** badges, and Home long-press shows active page widgets right at the top with 1-tap **`✕ Remove`** (`AppWidgetHost.deleteAppWidgetId` + persisted removal).
4. **System-Wide Audio Notification + Live Clock Standby Display (`NowPlayingManager` + `Arc7AudioService` + `Arc7DeskClockActivity`):**
   - Any audio playing across **`YouTube Music`**, **`YouTube`**, **`Music`**, **`Radio & Podcasts`**, **`Voice Recorder`**, or external players like **`VLC`** automatically posts an Android `4.2` compact + expanded media notification (`Prev / Play-Pause / Next / Stop`) AND renders live cover art, track title, artist, source badge, progress bar, and transport controls right on **Clock Standby**!
5. **Clean System App Naming (`Arc7 Freedom` Only):**
   - Only **`Arc7 Freedom`** carries `"Arc7"` in its name — every other built-in app, widget, launcher, store, and utility uses clean human names (`YouTube`, `YouTube Music`, `Browser`, `Clock`, `Books`, `Music`, `Calendar`, `Notes`, `Files`, `Photos`, `Camera`, `Calculator`, `Weather`, `PDF Reader`, `Radio & Podcasts`, `Voice Recorder`, `Games`, `Stream Deck`, `Wi-Fi & VPN`, `Home`, `Aurora Store`, etc.).
6. **Official Tailscale Auth with Google Sign-In (`TailscaleManager` + `QrCodeEncoder` + `Arc7VpnActivity`):**
   - Official Linux `3.4.5`-safe `tailscale_1.78.1_arm.tgz` + 100% standards-compliant **ISO/IEC 18004 Reed-Solomon QR code** for real `https://login.tailscale.com/a/...` Google OAuth sessions + 1-tap on-tablet Google Sign-In + live `BackendState` polling that activates `Arc7VpnService` automatically as soon as Google login finishes.
- **PC Stream Deck, School VPN Relay, USB & Home Wi-Fi Companion Scripts:**
  - **Windows / macOS / Linux PC Stream Deck Server + Live Web Studio (`:18991` / `:18995`):** **[`Start-PC-Stream-Deck.py`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-PC-Stream-Deck.py)** | **[`Start-PC-Stream-Deck.bat`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-PC-Stream-Deck.bat)** | **[`Start-PC-Stream-Deck.sh`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-PC-Stream-Deck.sh)**
  - **Personal Port-443 TLS 1.3 Stealth School VPN Home Relay:** **[`Start-PC-School-VPN-Relay.py`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-PC-School-VPN-Relay.py)**
  - **Chromebook / PC / Mac Extended `1280×800` Second Screen & Macro Deck Sender:** **[`Start-Kobo-Second-Screen.py`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-Kobo-Second-Screen.py)**
  - **Chromebook / Linux Wired USB Internet Bridge:** **[`Start-Chromebook-Wired-Internet.sh`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-Chromebook-Wired-Internet.sh)**
  - **Windows 10 / 11 Home Wi-Fi Rooting Server:** **[`Start-Kobo-WiFi-Server.bat`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-Kobo-WiFi-Server.bat)**
  - **macOS / Linux Home Wi-Fi Rooting Server:** **[`Start-Kobo-WiFi-Server.sh`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Start-Kobo-WiFi-Server.sh)**

---

## What's New in `v2.3.1` & `v2.3.0`

0. **Complete Hardware Fix for F-Droid `Error during update: Incorrect signature` (`v2.3.1` / `FDroidClassicLegacy v1303`):**
   - Eliminates Android `4.2.2` Apache Harmony `JarUtils.verifySignature()` throwing `SecurityException("Incorrect signature")` on `SHA-256` / `SHA256withRSA` repository `index-v1.jar` files (`IndexV1Updater.processDownloadedIndex` `new JarFile(indexFile, false)`) and installed APKs (`App.initInstalledApk` -> `Arc7Bridge.populateInstalledApkInfo`), verifying 100% of repos through our pure-Java `BigInteger.modPow` PKCS#1 v1.5 RSA (`1024–8192`-bit) + ITU-T X.690 BER indefinite-length (`0x80`) + DER + RFC 5652 CMS `SignedAttributes` PKCS#7 verifier!

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
