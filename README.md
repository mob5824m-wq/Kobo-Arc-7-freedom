# Kobo Arc 7 Freedom (`ca.arc7freedom.app`)

**Root, Full OS Partition Backup & Restore, and Clean Stock Android 4.2 Jelly Bean Installer for the Kobo Arc 7 (`T647` / `apollojr`, MediaTek MT8125) & Kobo Arc 7HD (`T416`, NVIDIA Tegra 3).**

---

## Ready-to-Install Signed APKs

Both pre-built, `zipalign`ed, **Android 4.2 Dalvik (`DEX 035`) + APK Signature v1 (JAR) / v2 / v3** signed APKs are ready right now in:

1. **[GitHub Releases (`v1.4.0` Latest)](../../releases/tag/v1.4.0)** — direct one-click download (`https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/KoboArc7Freedom.apk`)
2. **[`releases/KoboArc7Freedom.apk`](releases/KoboArc7Freedom.apk)** — Complete all-in-one Android 4.2 **7-App + 4-Service + 4-ARMv7-Binary** suite (`~11.7 MB` — pre-bundles **all 120 modern 2026 Root CAs offline**, **Wi-Fi File Drive + Remote `.APK` Installer + Live Screenshot Server (`:8080`)**, **`2026 Reader Lite` + TLS 1.3 Web Browser**, **System-Wide `TLS 1.3` Upgrade Proxy (`127.0.0.1:8118`)**, **Pre-Bundled Offline `F-Droid Classic` App Store**, **`Arc7 E-Reader` (`.epub`/`.txt`/`.html` + **70,000+ Free Project Gutenberg Storefront** + **Color `.CBZ` Comic/Manga Reader**)**, **`Arc7 Audio` Screen-Off Music & Audiobook Player (Auto-Bookmark + Sleep Timer)**, **`Arc7 Desk Clock` Smart Stand + Live TLS 1.3 Weather + Photo Frame**, **`Arc7 App Manager` (`Freeze / Export APK / Trim Caches`) + Wireless `ADB over Wi-Fi (:5555)`**, **Floating Soft-Button Pill (`Save Worn Power/Vol Buttons`)**, **`64 GB / 128 GB / 256 GB` MicroSDXC `FAT32` Card Formatter**, **`MT8125` CPU Power Profiles & Deep-Sleep Saver**, **MicroSD Storage Expander**, **On-Device Anti-Brick `MT8125` Root-ADB Patcher**, **Speed/RAM/Ad-Block/Night Filter/Battery Suite**, ARMv7 root engine, `su`, partition backup/flasher tool, AND `VanillaAndroid4Home.apk`!)
3. **[`releases/VanillaAndroid4Home.apk`](releases/VanillaAndroid4Home.apk)** — Standalone Clean Stock Android 4.2 Holo Home Launcher (`33 KB`, also automatically installed to `/system/app/` during One-Tap Stock Android 4 conversion)
4. **[`releases/SHA256SUMS.txt`](releases/SHA256SUMS.txt)** — SHA-256 verification digests

---

## Why Kobo Arc 7 Needs Special Handling

Kobo discontinued server support for the Arc tablet line, leaving many **Kobo Arc 7 (`T647` / `apollojr`, Android 4.2.2 Jelly Bean, Linux 3.4 ARMv7)** tablets locked behind dead Kobo account/OTA servers or heavily bloated by Kobo's custom Tapestry launcher.

Worse, generic Android root/debloat scripts often soft-brick Kobo Arc 7 tablets for three hardware-specific reasons — **all three are solved automatically in `KoboArc7Freedom.apk`**:

1. **Missing Provisioning Flags (`Home` Button Breakage):** Deleting `KoboSetupWizard.apk` without writing `Settings.Global.device_provisioned = 1` and `Settings.Secure.user_setup_complete = 1` leaves Android 4.2 convinced initial setup never finished — disabling the **Home** button, lockscreen, and notification shade. Both `KoboArc7Freedom` and `VanillaAndroid4Home`'s boot receiver enforce these flags automatically.
2. **Board Mismatch Brick Protection (`MT8125` vs `Tegra 3`):** The standard **Kobo Arc 7 (`T647` / `apollojr`)** uses a **MediaTek MT8125** SoC (`/emmc@android` -> `/dev/block/mmcblk0p5`), whereas the **Kobo Arc 7HD (`T416`)** uses an **NVIDIA Tegra 3** SoC (`/dev/block/platform/sdhci-tegra.3/by-name/{LNX,SOS,APP,CAC,UDA}`). `DeviceInspector` automatically detects the exact board and eMMC block layout at startup and blocks wrong-board images before touching flash storage.
3. **MediaTek Wi-Fi / MAC / Calibration NVRAM Safety:** Wiping or losing `nvram` / `protect_f` (`mmcblk0p2`) / `protect_s` (`mmcblk0p3`) permanently breaks MediaTek `mtk_stp_wmt` Wi-Fi calibration and MAC addresses. **Backup OS** backs up these partitions automatically and **One-Tap Stock Android 4 Conversion** leaves every kernel module, GPU library (`PowerVR SGX544MP`), touchscreen driver, and `/system/etc/firmware` blob intact.

---

## Quick Start: Getting `KoboArc7Freedom.apk` onto Your Kobo Arc 7 (3 Easy Ways!)

> **Important Tip:** Before `KoboArc7Freedom.apk` is installed, Kobo's stock 2013 Browser cannot open `https://github.com/...` directly because it lacks modern SSL certs & TLS 1.3. Use **any** of the 3 zero-hassle ways below once — after that, your tablet has its own **120 pre-downloaded 2026 CAs** + **`Arc7 Browser (TLS 1.3)`** right on the tablet!

### Method A — Zero-Cable Home Wi-Fi Installer *(Fastest — No USB Cable or MicroSD Card Needed!)*
1. Enable **Settings -> Security -> Unknown sources** on your Kobo Arc 7 and connect it to the same home Wi-Fi as your computer.
2. On your PC / Mac / Linux laptop, run:
   ```bash
   python3 tools/serve-over-wifi.py
   ```
3. Open Kobo's built-in Browser on the tablet and type the plain **`http://192.168.x.x:8000`** address shown in your terminal (plain local HTTP uses zero SSL certs — opens immediately!), then tap **Download KoboArc7Freedom.apk**!

### Method B — MicroSD Card (`file://` Browser Trick — No Computer Needed After Copying!)
1. Enable **Settings -> Security -> Unknown sources** on your Kobo Arc 7.
2. Copy **`KoboArc7Freedom.apk`** onto the root of a **FAT32 microSD card** and insert it into the Kobo Arc 7's microSD slot.
3. Open Kobo's built-in Browser and type exact address:
   ```text
   file:///storage/sdcard1/KoboArc7Freedom.apk
   ```
   Android's Package Installer opens immediately — tap **Install**!

### Method C — USB Cable (`adb`) / Rescue From Stuck Kobo Wi-Fi Setup Screen
If your Kobo Arc 7 was factory-reset and is stuck on Kobo's dead Wi-Fi Setup Wizard (with USB Debugging active), connect a micro-USB cable and run:

- **Linux / macOS:**
  ```bash
  ./tools/usb-sideload-and-root.sh
  ```
- **Windows:**
  Double-click **`tools\usb-sideload-and-root.bat`**

This installs `KoboArc7Freedom.apk` over USB AND launches `MainActivity` directly on top of Kobo's stuck Setup Wizard so you can immediately bypass Kobo Setup, root the tablet, back up your factory OS, and convert to clean Stock Android 4.2!

---

## Using the App (Step-by-Step)

### Tab `1. ROOT` — Native On-Device Root & Persistent `su`
- Displays your live hardware card (**Model**, **SoC (`MT8125` / `Tegra 3`)**, **Kernel version**, **Storage paths**, and **Root status**).
- Tap **`ROOT TABLET NOW (INSTALL PERSISTENT SU)`**:
  - Executes `libarc7root.so` across three automatic kernel root stages tailored for Android 4.2.2 ARMv7 Linux 3.4 kernels:
    1. Existing `su` upgrade/repair check
    2. **Stage 1 (`CVE-2013-6282`)** — ARMv7 `get_user` / `put_user` missing domain range check (`Linux < 3.5.5`, primary stage for Kobo Arc 7 `3.4.5` kernel) with dynamic kernel `cred` / `task_struct` scanning
    3. **Stage 2 (`MT8125` Driver Probe)** — MediaTek camera/isp/sysram/disp node elevation fallback
    4. **Stage 3 (`CVE-2014-3153`)** — `futex_requeue` PI kernel elevation fallback (`Linux 3.4.x < June 2014`)
  - Remounts `/system` read-write, installs static ARMv7 `su` (`06755` setuid root) to `/system/xbin/su` and `/system/bin/su`, writes `/system/etc/install-recovery.sh` so root daemon persistence survives reboots even on `nosuid` mounts, and verifies `uid=0(root)` execution live in the console.
- Includes one-tap **Unroot / Remove Persistent `su`** at any time.

### Tab `2. CERTS & NET` — Pre-Downloaded 2026 Network Certificates & TLS 1.3 Downloader
Stock Android 4.2.2 (`2013`) cannot connect to almost any modern website in 2026 for **four** reasons: missing modern Root CAs (*Let's Encrypt ISRG Root X1/X2, Google GTS R1, DigiCert G2, USERTrust*), expired cross-sign CAs (`DST Root CA X3`, which breaks Android 4.2's OpenSSL 1.0.1 chain validator), dead-battery clocks reset to `2013`, and disabled/missing `TLS 1.2` & `TLS 1.3` ciphers. **All four are solved offline right inside the APK:**
- **120 Pre-Downloaded 2026 Mozilla Root CA Certificates (`assets/cacerts/`):** Bundled **100% offline** inside `KoboArc7Freedom.apk` in exact Android `<subject_hash_old>.<n>` format — **zero internet connection needed to install them!**
- **`Install All 120 Pre-Downloaded Certs & Fix Internet Now`:**
  - Installs all 120 modern 2026 Root CAs into `/system/etc/security/cacerts/` and `/data/misc/keychain/cacerts-added/` (and also supports Android's non-root `KeyChain` Certificate Installer UI if tapped before rooting!).
  - Automatically backs up stock CAs and quarantines every expired `2013–2023` certificate (`DST Root CA X3`, `AddTrust`, etc.).
  - Syncs dead-battery `2013` clocks automatically over raw IPv4 UDP **NTP** (`162.159.200.1`, `216.239.35.0`) with plain HTTP port-80 `Date:` header fallback, and sets clean IPv4 DNS (`1.1.1.1`, `8.8.8.8`).
- **Built-In ARMv7 MbedTLS 3.6 (`TLS 1.3` + `TLS 1.2`) Downloader & Local Browser Gateway (`libarc7tls.so`):**
  - Paste any `https://...` URL to download Android `.zip` ROMs, `recovery.img`, or `.apk` apps over modern `TLS 1.3` using the bundled 2026 CAs (works even before rooting!), then jump straight to flashing/installing with one tap.
  - Tap **`Open TLS 1.3 Browser Portal`** (`http://127.0.0.1:8118/`) to browse modern HTTPS websites, run web searches, and click `.zip` / `.apk` downloads directly inside Kobo Arc 7's stock Browser!

### Tab `3. BACKUP OS` — Full Factory OS & Partition Backup / Restore
- Auto-selects your **External MicroSD card (`/storage/sdcard1`)** when inserted (or internal storage otherwise) and verifies free space before starting.
- **Full Factory OS Backup (Recommended First Step)** creates a timestamped folder `/storage/sdcard1/KoboArc7Backups/<YYYYMMDD-HHMMSS>/` containing:
  - Raw streaming SHA-256 verified dumps of critical partitions (`mbr`, `ebr1`, `pro_info`, `nvram`, `protect_f`, `protect_s`, `sec_ro`, `uboot`/`lk`, `logo`, `boot`, `recovery` on MT8125; `LNX`, `SOS` on Tegra 3)
  - Compressed streaming `system.img.gz` image of the entire factory `/system` partition (`mmcblk0p5` / `APP`) built on-the-fly with static `zlib` (`libarc7tool.so`) so even large `/system` partitions fit comfortably on FAT32 microSD cards (no 4 GB file limit overflow!)
  - `build.prop`, partition table dumps (`/proc/dumchar_info`, `/proc/emmc`, `/proc/partitions`, `/proc/mounts`), auto-generated **SP Flash Tool `MT8125_Android_scatter.txt`**, `backup-manifest.json`, and `SHA256SUMS.txt`
- **Verify Backup Integrity** recomputes every SHA-256 digest byte-for-byte against `SHA256SUMS.txt`.
- **Restore Backup** verifies checksums first and restores `/system` + `boot` + `recovery` (or boot/recovery only) back to factory state.

### Tab `4. ANDROID 4` — Install Normal Stock Android 4 (Both Modes Included!)

#### Mode A — One-Tap Clean Stock Android 4.2 Jelly Bean Conversion *(Recommended — Safest)*
Converts your Kobo Arc 7 into a fast, pure **Stock Android 4.2.2 Holo Tablet** while keeping 100% of your working hardware drivers (Wi-Fi, Bluetooth, touchscreen, audio, cameras, microSD, and PowerVR GPU acceleration):
1. Installs **Vanilla Android 4 Home (`VanillaAndroid4Home.apk`)** into `/system/app/VanillaAndroid4Home.apk` FIRST so a working AOSP-style Holo launcher + 6-column searchable App Drawer + clock/search/Quick Settings bar is guaranteed on the tablet before touching Kobo's launcher.
2. Archives Kobo's lock-in, Tapestry launcher, forced Setup Wizard, and bundled bloatware into `/sdcard/KoboArc7Backups/kobo-bloat-backup/` (nothing is permanently destroyed — **One-Tap Restore Kobo Apps** brings them back anytime!).
3. Sets `Settings.Global.device_provisioned = 1` & `Settings.Secure.user_setup_complete = 1`, tunes `build.prop` (`dalvik.vm.heapgrowthlimit`, clean lockscreen, disables dead Kobo OTA polling), sets `VanillaAndroid4Home` as the active `HOME` activity, clears Dalvik cache, and offers a one-tap reboot into clean Stock Android 4.2.

#### Mode B — Custom ROM `.zip` & Custom `recovery.img` (TWRP / CWM) Flasher
For power users installing custom AOSP / CyanogenMod 10.1 / Lineage Android 4 `.zip` packages or custom recoveries:
- **Custom ROM `.zip` Installer:** Built-in storage browser inspects `META-INF/com/google/android/updater-script` inside the `.zip`, checks for **MT8125 vs Tegra 3 board mismatches**, stages the `.zip` for recovery, writes `/cache/recovery/openrecoveryscript` + `/cache/recovery/command`, and reboots straight into `recovery` to flash automatically (with optional `wipe data` / `wipe cache`).
- **`recovery.img` / `boot.img` Flasher:** Validates the 2048-byte `ANDROID!` kernel+ramdisk header (`arc7tool inspect-boot`), automatically backs up your existing partition first, flashes + verifies SHA-256 readback, and reboots into recovery.

### Tab `5. TOOLS` — Power Utilities
- **Bypass Stuck Kobo Setup Wizard Now** (fixes disabled Home button & notification shade instantly)
- **Mount `/system` Read-Write (`RW`) / Read-Only (`RO`)**
- **Install Any `.apk` as System or User App** (handy for sideloading Aurora Store / F-Droid Classic / NewPipe Legacy / Firefox / VLC on Android 4.2)
- **Reboot Manager** (`Normal Reboot`, `Reboot to Recovery`, `Reboot to Bootloader`)

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
| External MicroSD | `/storage/sdcard1` (`/dev/block/vold/179:97`, `vfat`) | — |

---

## Building from Source & Running Verification Tests

### Project Layout
```text
Kobo-Arc-7-freedom/
├── app/src/main/                  # Main Android app (ca.arc7freedom.app, API 15-17)
│   ├── AndroidManifest.xml
│   ├── assets/VanillaAndroid4Home.apk
│   └── java/ca/arc7freedom/app/
│       ├── MainActivity.java      # Responsive 4-tab Android 4.2 Holo UI + Live Console
│       ├── DeviceInspector.java   # MT8125 vs Tegra 3 detector, volume probe & scatter builder
│       ├── RootShell.java         # Persistent root shell, CVE runner & su installer
│       ├── BackupManager.java     # Streaming SHA-256 + gzip OS backup, verifier & restorer
│       ├── Android4Installer.java # One-tap Stock Android 4 converter + ROM zip/img flasher
│       └── FilePickerDialog.java  # MicroSD / internal storage file & folder picker
├── launcher-android4/             # Clean Stock Android 4.2 Holo Home Launcher (VanillaAndroid4Home)
├── native/                        # Static ARMv7 EABI5 C binaries (compiled via zig cc -static)
│   ├── arc7_common.h              # Shared logging, mount remounting & property helpers
│   ├── su.c                       # Setuid + daemon fallback root shell binary (libarc7su.so)
│   ├── arc7root.c                 # Multi-stage Android 4.2 ARMv7 kernel root engine (libarc7root.so)
│   └── arc7tool.c                 # Streaming zlib + FIPS-180-4 SHA-256 partition tool (libarc7tool.so)
├── scripts/
│   ├── generate-icons.py          # Multi-density launcher icon generator (mdpi..xxhdpi)
│   ├── build-all.sh               # Full reproducible C + Java + DEX 035 + v1/v2/v3 APK builder
│   ├── verify-apks.py             # Automated zipalign, apksigner, AXML, DEX 035, ELF32 & JVM tests
│   └── bootstrap-toolchain.sh     # Self-contained Linux build toolchain bootstrapper
├── tools/
│   ├── usb-sideload-and-root.sh   # Linux/macOS USB ADB installer & stuck-Setup rescue helper
│   └── usb-sideload-and-root.bat  # Windows USB ADB installer & stuck-Setup rescue helper
└── releases/
    ├── KoboArc7Freedom.apk        # Signed ready-to-install main APK
    ├── VanillaAndroid4Home.apk    # Signed standalone Stock Android 4.2 Launcher APK
    └── SHA256SUMS.txt             # SHA-256 digests
```

### Build & Test Commands
```bash
# Build both signed APKs + ARMv7 native binaries from scratch:
make apks     # or ./scripts/build-all.sh

# Run the complete binary, signature, DEX 035, ELF32 ARMv7 & JVM test suite:
make verify   # or python3 scripts/verify-apks.py
```
