# Kobo Arc 7 Freedom (`ca.arc7freedom.app` — `v1.8.2`)

![Kobo Arc 7 Freedom Logo Banner](docs/logo-banner.png)

**Anti-Brick Verified Root, Built-In 4-Tier Pure-C DNS + Dual-Engine `TLS 1.0–1.3` Internet Gateway, Working F-Droid Classic Store, Direct USB ADB Laptop Backup & Restore (`0 MB` Tablet Space), Clean Stock Android 4.2 Home Launcher + Live Widgets, 120 Offline 2026 Root CAs & Complete 10-Activity Suite for the Kobo Arc 7 (`T647` / `apollojr`, MediaTek MT8125) & Kobo Arc 7HD (`T416`, NVIDIA Tegra 3).**

---

## Ready-to-Install Signed Downloads (`v1.8.2` — Latest)

All pre-built, `zipalign`ed, **Android 4.2 Dalvik (`DEX 035`) + APK Signature Scheme v1 (JAR) / v2 / v3** APKs are permanently signed with [`keystore/arc7freedom-release.jks`](keystore/arc7freedom-release.jks) (`SHA256: 11:FD:17:ED:80:C9:13:EF:CB:F9:8F:76:73:DA:00:90:42:E7:64:7E:44:E5:D6:C5:F4:2F:CA:A5:CE:4C:D1:0F`) so all future updates install cleanly in-place:

> ### ⚡ Updating Your Kobo Arc 7 to `v1.8.2` (Fixes Browser DNS `"failing to get IP addresses"` & F-Droid Index Download!)
> - **Already on `v1.8.1`?** Simply download & install **[`KoboArc7Freedom.apk` (`v1.8.2`)](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/KoboArc7Freedom.apk)** right over top! It uses the exact same permanent release certificate (`11:FD:17:ED:...`), automatically hot-swaps the running `127.0.0.1:8118` TLS 1.3 + DNS daemon immediately on install (`ACTION_MY_PACKAGE_REPLACED` — **no reboot needed!**), and automatically clears any corrupt cached `502` index file in F-Droid Classic so pulling down to refresh in F-Droid works right away!
> - **Still on an older `v1.5.0` – `v1.8.0` build?** Download & open **[`Upgrade-My-Kobo-To-v1.8.2.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Upgrade-My-Kobo-To-v1.8.2.apk)** (`ca.arc7freedom.upgrader`, `~11.5 MB`) once to migrate signing keys, install `/sbin/{su,uname,cp}`, install `VanillaAndroid4Home v48` + `FDroidClassicLegacy`, and populate all 120 modern 2026 Root CAs in 1 tap.

| File / Tool | Direct Download (`v1.8.2` Release) | What It Does |
|---|---|---|
| **Web Installer Portal** | **[Open Live Installer Portal (`docs/index.html`)](https://mob5824m-wq.github.io/Kobo-Arc-7-freedom/)** | Browser download portal + 1-click USB/Wi-Fi helper launchers |
| **`KoboArc7Freedom.apk`** | **[Download Main Suite APK (`v1.8.2`)](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/KoboArc7Freedom.apk)** | **Direct in-place update for `v1.8.1` + fresh Kobo installs:** Built-In 4-Tier Pure-C DNS + TLS 1.3 Browser, F-Droid fix & full 10-Activity suite (`~11.5 MB`) |
| **`Upgrade-My-Kobo-To-v1.8.2.apk`** | **[Download 1-Tap Upgrader APK (`v1.8.2`)](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Upgrade-My-Kobo-To-v1.8.2.apk)** | **For pre-`v1.8.1` Kobo installs (or full refresh):** upgrades all APKs, cleans F-Droid cache, provisions `/system/etc/resolv.conf` & installs 120 2026 CAs |
| **`VanillaAndroid4Home.apk`** | **[Download Stock Android 4 Home (`v48`)](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/VanillaAndroid4Home.apk)** | Clean Stock Android 4.2 Holo Home Launcher with live **Home Screen Widgets**, isolated app task launching & 12 HD Wallpapers |
| **`Backup-Kobo-To-Laptop-ADB.bat`** | **[Download Windows USB Backup & Auto-Upgrader](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Backup-Kobo-To-Laptop-ADB.bat)** | Windows 10/11 1-click tool: auto-installs Google `adb`, auto-upgrades older tablet builds, and streams SHA-256-verified backups over USB using **`0 MB` tablet space** |
| **`Backup-Kobo-To-Laptop-ADB.sh`** | **[Download macOS / Linux USB Backup & Auto-Upgrader](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Backup-Kobo-To-Laptop-ADB.sh)** | macOS / Linux 1-click USB ADB auto-upgrader + direct flash/files streaming backup with SHA-256 verification (`0 MB` tablet space) |
| **`Start-Kobo-WiFi-Server.bat`** | **[Download Windows Home Wi-Fi Server](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Start-Kobo-WiFi-Server.bat)** | Windows 10/11 zero-Python 1-click home Wi-Fi server — auto-opens Windows Firewall port `8000` and prints your plain `http://192.168.x.x:8000` Kobo URL |
| **`Start-Kobo-WiFi-Server.sh`** | **[Download macOS / Linux Home Wi-Fi Server](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Start-Kobo-WiFi-Server.sh)** | macOS / Linux 1-click local HTTP sideload server on port `8000` |
| **`SHA256SUMS.txt`** | **[Download SHA-256 Digests](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/SHA256SUMS.txt)** | Cryptographic SHA-256 checksums for every release artifact |

---

## What's New & Fixed on Real Kobo Arc 7 Hardware (`v1.8.2`)

1. **Built-In 4-Tier Pure-C Android DNS Engine in `native/arc7tls.c` (Fixes Browser `"failing to get IP addresses"` & F-Droid `502` Errors):**
   - Static `musl` binaries previously relied on `/etc/resolv.conf` (which Android 4.2.2 omits, causing `getaddrinfo()` to fail against closed `127.0.0.1:53` with `MBEDTLS_ERR_NET_UNKNOWN_HOST`).
   - `arc7tls` now includes a self-contained **4-Tier Pure-C DNS Resolver**: **128-entry Thread-Safe RAM TTL Cache (`<0.001ms`)** -> **RFC 1035 UDP `:53`** (reading Android `getprop dhcp.wlan0.dns1/2` + `net.dns1/2` + `/system/etc/resolv.conf` + `1.1.1.1` / `8.8.8.8` / `9.9.9.9`) -> **RFC 1035 TCP `:53` automatic fallback** -> **TLS 1.3 RFC 8484 DNS-over-HTTPS (`https://1.1.1.1/dns-query` & `https://8.8.8.8/dns-query`)**, plus full `-DMBEDTLS_THREADING_PTHREAD` mutex safety across concurrent worker threads!
2. **Complete F-Droid Classic Fix (Immortal `oom_score_adj = -1000` Gateway + Instant Hot-Swap + Cache Clean-Slate):**
   - `libarc7tls.so` now runs detached (`setsid()`, `SIGHUP`-immune, `oom_score_adj = -1000`) so Android 4.2's `lowmemorykiller` never terminates `127.0.0.1:8118` while F-Droid unpacks & parses the `45 MB` repository index JSON on 1 GB RAM hardware, starts automatically on boot & `MY_PACKAGE_REPLACED`, hot-swaps any older daemon via `/__ping` (`ARC7_TLS13_READY_V182`) + `/__shutdown`, and performs a one-time `pm clear eu.bubu1.fdroidclassic.debug` to wipe any poisoned `502` partial download files.
3. **100% Stock Android 4.2 `toolbox` Compatibility + Fast Logged Stock-Android Conversion (`Android4Installer`):**
   - Pre-checks installed Kobo packages via `PackageManager` in Java (`<5 ms`) with `</dev/null` stream isolation and real-time step logs so conversion completes cleanly in seconds with zero silent pauses, alongside multi-call `/sbin/{su,uname,cp}`, **Home Screen Widgets (`v48`)**, **10 isolated app task stacks**, and **Direct USB ADB Laptop Backup (`:18777`)**.

---

## Why Kobo Arc 7 Hardware Requires Anti-Brick Guards

Kobo discontinued server support for the Arc tablet line, leaving many **Kobo Arc 7 (`T647` / `apollojr`, Android 4.2.2 Jelly Bean, Linux 3.4 ARMv7)** tablets locked behind dead Kobo account/OTA servers or heavily slowed down by Kobo's Tapestry launcher. Generic Android debloat/root scripts can soft-brick Kobo Arc 7 hardware for three hardware-specific reasons — **all three are protected automatically**:

1. **Missing Provisioning Flags (`Home` Button Breakage):** Deleting `KoboSetupWizard.apk` without setting `Settings.Global.device_provisioned = 1` and `Settings.Secure.user_setup_complete = 1` disables Android 4.2's **Home** button, lockscreen, and notification shade. Both `KoboArc7Freedom` and `VanillaAndroid4Home` enforce these flags automatically.
2. **Board Mismatch Brick Protection (`MT8125` vs `Tegra 3`):** The **Kobo Arc 7 (`T647` / `apollojr`)** uses a **MediaTek MT8125** SoC (`/emmc@android` -> `/dev/block/mmcblk0p5`), whereas the **Kobo Arc 7HD (`T416`)** uses an **NVIDIA Tegra 3** SoC (`/dev/block/platform/sdhci-tegra.3/by-name/{LNX,SOS,APP,CAC,UDA}`). `DeviceInspector` detects the exact board at startup and blocks wrong-board images before touching flash storage.
3. **MediaTek Wi-Fi / MAC / Calibration NVRAM Safety:** Losing `nvram` / `protect_f` (`mmcblk0p2`) / `protect_s` (`mmcblk0p3`) breaks MediaTek `mtk_stp_wmt` Wi-Fi calibration and MAC addresses. **Backup OS** & **USB ADB Backup** save these partitions automatically, and **Stock Android 4 Conversion** keeps every kernel module, `PowerVR SGX544MP` GPU library, touchscreen driver, and `/system/etc/firmware` blob untouched.

---

## Quick Start: Installing onto Your Kobo Arc 7 (3 Easy Ways)

> **Tip:** Factory Kobo OS (2013) cannot open `https://github.com/...` in Kobo's stock Browser before `KoboArc7Freedom.apk` is installed because factory OS lacks 2026 SSL roots & TLS 1.3. Use **any** of the 3 ways below once — after that, your Kobo has all **120 modern 2026 Root CAs** + **`Arc7 Browser (TLS 1.3)`** built right in!

### Method A — Zero-Cable Home Wi-Fi Server *(Fastest — Zero USB Cable, Zero MicroSD, Zero Python on Windows)*
1. Enable **Settings -> Security -> Unknown sources** on your Kobo Arc 7 and join the **same home Wi-Fi network** as your computer.
2. Start the local home Wi-Fi server on your computer:
   - **Windows 10 / 11:** Double-click **`Start-Kobo-WiFi-Server.bat`** (opens Windows Firewall port `8000` automatically and displays your real LAN `http://192.168.x.x:8000` URL).
   - **macOS / Linux:** Run `./releases/Start-Kobo-WiFi-Server.sh` or `python3 tools/serve-over-wifi.py`.
3. Open Kobo's Browser and type the exact plain **`http://192.168.x.x:8000`** URL (**include `http://`, NOT `https://`**):
   - Tap **`Upgrade-My-Kobo-To-v1.8.1.apk`** if upgrading from an earlier `v1.5.0–v1.8.0` version, **or**
   - Tap **`KoboArc7Freedom.apk`** on a brand-new factory Kobo Arc 7!

### Method B — Direct USB Cable Auto-Install / Upgrade + Instant Laptop Backup
1. Enable **Settings -> Developer options -> USB debugging** and plug your Kobo Arc 7 into your laptop with a data-capable Micro-USB cable.
2. Run **`Backup-Kobo-To-Laptop-ADB.bat`** (Windows) or **`./releases/Backup-Kobo-To-Laptop-ADB.sh`** (macOS / Linux) — or `./tools/usb-sideload-and-root.sh` if stuck on Kobo's initial Wi-Fi Setup Wizard.
3. It automatically installs or upgrades `KoboArc7Freedom v1.8.1` on the tablet and saves a SHA-256-verified backup directly onto your laptop Desktop (`0 MB` of tablet space used).

### Method C — MicroSD Card (`file://` Browser Trick — No Computer Needed After Copying)
1. Enable **Settings -> Security -> Unknown sources** on your Kobo Arc 7.
2. Copy **`Upgrade-My-Kobo-To-v1.8.1.apk`** (or **`KoboArc7Freedom.apk`**) onto a **FAT32 MicroSD card** and insert it into the Kobo Arc 7 slot.
3. Open Kobo's Browser and enter:
   ```text
   file:///storage/sdcard1/Upgrade-My-Kobo-To-v1.8.1.apk
   ```
   Tap **Install** -> **Open**!

---

## Full Suite Overview (5 Tabs + 10 Standalone Activities + 3 Home Widgets)

- **Tab `1. ROOT`:** Multi-stage native ARMv7 kernel root engine (`libarc7root.so`: `CVE-2013-6282` + MT8125 driver probe + `CVE-2014-3153`), persistent setuid/daemon multi-call `/sbin/{su,uname,cp}` + `/system/xbin/{su,uname,cp}`, root shell self-test, and 1-tap unroot.
- **Tab `2. CERTS & NET`:** 120 offline pre-packaged 2026 Mozilla Root CAs (`<subject_hash_old>.0`), expired `DST Root CA X3` quarantine, raw UDP NTP + HTTP clock sync, `1.1.1.1`/`8.8.8.8` DNS setup, persistent Dual-Engine BearSSL + MbedTLS `TLS 1.3` proxy (`127.0.0.1:8118`), and built-in HTTPS downloader.
- **Tab `3. BACKUP OS`:** Direct **USB ADB Laptop Streaming Backup (`:18777`, `0 MB` tablet space)** + on-device streaming gzip/SHA-256 partition backup (`mbr`, `ebr1`, `nvram`, `protect_f`, `protect_s`, `uboot`, `boot`, `recovery`, `system.img.gz`, `MT8125_Android_scatter.txt`), integrity verifier, and partition restorer.
- **Tab `4. ANDROID 4`:** Safe **One-Tap Stock Android 4.2 Holo Conversion** (`VanillaAndroid4Home v48` with Home Widgets, reversible Kobo bloat archive, `device_provisioned=1`, `build.prop` tuning) + **Custom ROM `.zip` & `recovery.img` / `boot.img` Flasher** with board-mismatch & header brick guards.
- **Tab `5. APPS, SD & POWER`:**
  - **Standalone Apps (Each in Its Own Isolated Task Stack):** `Arc7 Browser (TLS 1.3)`, `Arc7 E-Reader` (`.epub` / `.pdf` / `.txt` + 70,000 Project Gutenberg Store + `.cbz` Comic Reader), `Arc7 Audio` (music & audiobooks + sleep timer + home widget), `Arc7 Clock` (Alarms, World Clock, Timer, Stopwatch), `Arc7 Nightstand Desk Clock`, `Arc7 12 HD Wallpapers`, `Arc7 App Manager`, `Arc7 Root Terminal`, and **Patched `FDroidClassicLegacy`** (4,000+ apps).
  - **Storage, Display & Battery Tools:** **64 GB / 128 GB / 256 GB MicroSDXC `FAT32` Formatter** (with soldered-eMMC triple hardware guard), Heavy-Folder MicroSD Bind Mount, Persistent **Wi-Fi Drive Server (`:8080`)**, Wireless ADB (`:5555`), Warm Night Reading Filter, Floating Soft-Keys, System `/etc/hosts` Ad-Blocker, and CPU Power Profiles.

---

## Hardware Partition Map Reference

| Partition Role | Kobo Arc 7 (`T647` / `apollojr` — MediaTek MT8125) | Kobo Arc 7HD (`T416` — NVIDIA Tegra 3) |
|---|---|---|
| Kernel / Boot | `/dev/bootimg` | `/dev/block/platform/sdhci-tegra.3/by-name/LNX` |
| Recovery | `/dev/recovery` | `/dev/block/platform/sdhci-tegra.3/by-name/SOS` |
| System (`/system`) | `/emmc@android` (`/dev/block/mmcblk0p5`, 768 MB `ext4`) | `/dev/block/platform/sdhci-tegra.3/by-name/APP` |
| Cache (`/cache`) | `/emmc@cache` (`/dev/block/mmcblk0p6`, 512 MB `ext4`) | `/dev/block/platform/sdhci-tegra.3/by-name/CAC` |
| Userdata (`/data`) | `/emmc@usrdata` (`/dev/block/mmcblk0p7`, ~6 GB `ext4`) | `/dev/block/platform/sdhci-tegra.3/by-name/UDA` |
| Wi-Fi / NVRAM Calibration | `/dev/nvram`, `/emmc@protect_f` (`mmcblk0p2`), `/emmc@protect_s` (`mmcblk0p3`) | Factory calibration partition |
| External MicroSD | `/storage/sdcard1` (`/dev/block/mmcblk1p1`, `vfat` up to 256 GB) | `/storage/sdcard1` |

---

## Building from Source & Running Verification Gates

### Project Layout
```text
Kobo-Arc-7-freedom/
├── keystore/
│   └── arc7freedom-release.jks           # Permanent version-controlled release keystore (v1+v2+v3)
├── app/src/main/                         # Main suite APK (ca.arc7freedom.app, v1.8.1, API 15-17)
│   ├── AndroidManifest.xml               # 10 isolated taskAffinity activities + 6 services + 3 widgets
│   ├── assets/                           # Bundled 120 2026 CAs, VanillaAndroid4Home.apk & FDroidClassicLegacy.apk
│   └── java/ca/arc7freedom/app/          # UI, Root, Certs, USB/SD Backup, Browser, Reader, Audio, Clock, Widgets
├── upgrader/src/main/                    # 1-Tap Signature-Conflict-Free Root Upgrader (ca.arc7freedom.upgrader)
│   ├── AndroidManifest.xml
│   └── java/ca/arc7freedom/upgrader/UpgraderActivity.java
├── launcher-android4/                    # Stock Android 4.2 Holo Home Launcher v48 w/ AppWidgetHost (ca.arc7freedom.launcher)
├── native/                               # Static ARMv7 EABI5 C binaries (compiled via zig cc -static)
│   ├── arc7_common.h                     # Shared logging, mount remounting & multi-call applet symlinks
│   ├── su.c                              # Multi-call setuid/daemon `su` + built-in POSIX `uname` & `cp` applets
│   ├── arc7root.c                        # Multi-stage Android 4.2 Linux 3.4 ARMv7 kernel root engine
│   ├── arc7tool.c                        # Streaming zlib + FIPS-180-4 SHA-256 partition & 256GB FAT32 tool
│   └── arc7tls.c                         # Dual-engine BearSSL (TLS 1.0-1.2) + MbedTLS 3.6 (TLS 1.3) gateway
├── scripts/
│   ├── build-all.sh                      # Reproducible full build for all 4 APKs + static portals + SHA256SUMS
│   ├── patch-fdroid-apk.py               # Binary resources.arsc patcher for F-Droid TLS 1.3 index bridge
│   ├── generate-cacerts.py               # Offline 120-cert Mozilla 2026 CA packager (<subject_hash_old>.0)
│   ├── generate-icons.py                 # Vector shield logo banner & 8 multi-DPI app icon renderer
│   └── verify-apks.py                    # End-to-end cert, DEX 035, ELF32, pure-toolbox, TLS 1.3 & HTTP verification
├── tools/
│   ├── serve-over-wifi.py                # Local HTTP/1.1 + Range + fast TLS-reject Wi-Fi installer & portal generator
│   ├── usb-sideload-and-root.sh          # Linux/macOS USB ADB installer & stuck Setup Wizard rescue script
│   └── usb-sideload-and-root.bat         # Windows USB ADB installer & stuck Setup Wizard rescue script
├── docs/                                 # GitHub Pages live web installer portal
└── releases/                             # Signed ready-to-install v1.8.1 APKs, USB/Wi-Fi launchers & SHA256SUMS.txt
```

### Reproducible Build & Verification Commands
```bash
# Build all signed APKs (Main v1.8.1, 1-Tap Upgrader, Home Launcher v48, Patched F-Droid) + ARMv7 binaries:
./scripts/build-all.sh

# Run every automated verification gate (permanent cert SHA-256, zipalign, v1/v2/v3 signatures,
# Dalvik DEX 035, ELF32 ARMv7, multi-call su/uname/cp, zero non-toolbox shell calls & live TLS 1.3):
python3 scripts/verify-apks.py
```
