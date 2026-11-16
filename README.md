# Arc7 Freedom (`v2.5.3` — Android 4.x maintenance build)

![Kobo Arc 7 Freedom Logo Banner](releases/logo-banner.png)

**Kobo Arc 7 / Arc 7HD (Android 4.0.3–4.2.2) daily-driver ecosystem, now at `v2.5.3`
(`versionCode=253`).**

## In this branch: notification / playback repairs + independent apps + Bluetooth internet

**Requirement update:** the earlier requirement to have all network traffic go through
Kobo Arc 7 Freedom is **withdrawn**. This does not require removing existing work.
Independent per-app networking remains the current default; existing optional proxy,
VPN and USB-networking tools are retained.

The updated APKs have been **rebuilt and signed locally**, but **not published as a new
GitHub release**. This development build retains the existing version numbers (suite
`2.5.3 / 253`), so install the rebuilt APKs explicitly even if Android shows the same version.
Update **the separate APKs as well as Arc7 Freedom and Home**; installing only the main APK
cannot replace code in already-installed apps.

- Main app, Home, Aurora, F-Droid, YouTube and YouTube Music: **`releases/`**.
- All 28 default apps, including **`Bluetooth-Internet.apk`**: **`releases/apps/`**, generated
  by the build and intentionally excluded from Git. Use the local Wi-Fi portal to install
  these; the published GitHub “latest” download has not been changed.
- **`releases/SHA256SUMS.txt`** covers the rebuilt set. Prefer in-place Android updates or
  `adb install -r path/to/App.apk` with this repository's permanent signing key. Back up
  data first if a very old installation uses a different signing key; do not uninstall or
  clear data merely because its version number is unchanged.

### Notification, playback, installer and obsolete-proxy repairs

**Install checklist: [releases/INSTALL-RUNTIME-FIXES.md](releases/INSTALL-RUNTIME-FIXES.md).**
This is a same-version maintenance build, not a newly published release.

- **VLC and other players own their notifications.** Arc7's external-audio observers update
  Clock/widgets silently; only a prepared player in the actual owning APK can post Arc7
  media controls. Update the suite **and installed Clock, Music, Radio & Podcasts, Voice
  Recorder, YouTube and YouTube Music APKs**, then reboot/force-stop their old processes.
  Updating only the suite cannot remove watchers running older standalone code. Keep
  official VLC and its own notification; it does not need replacing for this repair.
- **YouTube playback is separate from metadata.** The resolver uses the maintained native
  visionOS 1.04 request shape (matching UA/device/WATCH/MOBILE context, per-client visitor
  data, mobile `t`/`id` parameters and playback nonce), then iOS / TV fallbacks. HLS is
  parsed/probed and relayed through the owning APK's TLS engine, not handed to old
  Stagefright as an HTTPS MP4. Finite fMP4 HLS and DASH are disk-spooled into classic MP4/M4A
  with H.264/AAC tracks. Unsupported codecs/DRM and failed streams are reported honestly.
  An unknown-length preparation never advertises partial output as a finished file; a
  stalled preparation returns an error. Playback readiness follows player preparation and
  start, with generation guards, cancellation and a 125-second preparation timeout.
- **App Info/uninstall targets the real app.** Updated Home canonicalizes legacy Package
  Installer shortcuts to `ca.arc7freedom.packageinstaller`, without substituting the suite
  if that separate app is absent. The optional updater is `ca.arc7freedom.upgrader` and has
  its own **App Info / uninstall this upgrader** button. In-place suite/Home/media/store
  updates no longer silently uninstall on signature/storage errors. The explicitly
  destructive **Clean Reinstall** action has an additional package-specific confirmation.
- **The suite retries obsolete global and saved-Wi-Fi proxy removal automatically.** Only
  `localhost:8118` / `127.0.0.1:8118` are matched. Other proxies, credentials, static IP/DNS,
  VPNs and USB routes are preserved. Cleared Wi-Fi entries do not hide a remaining global
  proxy; denied/unverifiable cleanup remains pending and shows manual instructions.

**Validation boundary:** offline tests exercise actual visitor/player HTTP requests against
an enforcing fixture gateway, real synthetic H.264/AAC media, MP4/M4A decoding with FFmpeg,
Range/HEAD/error behavior, pending-file/cancellation races and fake Android ownership/proxy
APIs. **They do not prove live YouTube access or playback/notifications on a physical Kobo.**
Those still require a tablet test; age/sign-in restrictions, server changes, codecs and ROM
permissions can prevent playback. Finite fMP4 needs cache space and may take time to prepare.

### No more shared network funnel

- The 28 default apps and YouTube / YouTube Music launch their **own packages**, including
  from Home's dock, the shared app bar, file-open shortcuts and widgets. An absent app shows
  installation help instead of silently running another copy inside Arc7 Freedom.
- Each network-enabled APK starts its own native TLS engine **under its own Android UID**,
  on a stable package-specific loopback port. Sharing Java source/class names does not share
  a process, private data directory, permissions or network accounting. Offline apps such as
  Calculator and Notes do not even request `INTERNET` or run a gateway.
- Aurora Store and patched F-Droid also bundle their **own** native engine and CA bundle
  (`21118` and `21120`, respectively); neither needs the suite running. F-Droid migrates old
  `8118` repository addresses without changing repository certificate pins. Official VLC
  already owns its network stack; the optional fallback player now owns its TLS bridge too.
- Certificate installation no longer enables a global HTTP proxy. On startup/upgrade the
  suite retries removal of **only its old `localhost:8118` / `127.0.0.1:8118` global and saved
  Wi-Fi proxies**. Global changes normally require root; saved Wi-Fi cleanup uses the ROM's
  Wi-Fi API where permitted. **Use Independent Networking** retries manually. If cleanup
  remains pending, change only networks with that old proxy to **None** in Android settings;
  unrelated work/school proxies are left alone.
- The optional **legacy global proxy**, an explicitly enabled VPN, or the userspace USB
  internet tunnel can still combine traffic under their owner's UID on Android 4.x. Turn
  those modes off when you want direct per-app Wi-Fi/Bluetooth accounting. Port `8118`
  remains available for third-party apps that you deliberately configure to use it.
- Existing standalone app data is retained by in-place APK updates. Data from old *in-suite*
  copies stays in the suite and is **not silently shared, deleted or moved**. Export/import
  anything you need from those old copies. Clock/Calendar schedules and Clock/Calendar/
  Weather/Music widgets now belong to their own apps; replace legacy suite widgets with the
  corresponding standalone widget to see that app's data.

### Share Wi-Fi by Bluetooth

Install **`Bluetooth-Internet.apk`** (`ca.arc7freedom.bluetoothinternet`). Open it from the
app drawer, Arc7 Freedom's network tab, or Wi-Fi & VPN's Bluetooth button.

**Kobo Wi-Fi → another device:**

1. Connect the Kobo to Wi-Fi and enable Bluetooth.
2. Use **Pair devices / Bluetooth settings** to pair the phone/computer.
3. Tap **Share Kobo Wi-Fi via Bluetooth**, or enable **Bluetooth tethering** in **Android
   tethering settings** if the ROM restricts app control.
4. On the receiving device, select the Kobo and enable **Internet access / Connect to network**.

**Phone/computer → Kobo:** enable Bluetooth tethering on the other device, pair it, select
it in Bluetooth Internet, and tap **Connect to selected device's internet**. Some phones
share mobile data only, rather than their Wi-Fi; some Kobo ROMs support sharing but not
receiving Bluetooth PAN. Follow the settings fallback when the ROM denies the hidden PAN API.

**Hardware limit:** some Kobo Arc variants have **no Bluetooth adapter**. The app checks for
this and says so; software cannot add the missing radio. Use Wi-Fi or USB Internet instead.
A paired device or connected PAN link alone is **not proof of internet access**. The UI
reports OS connection/tethering state, not a fabricated successful internet test. Routing
and DHCP are handled by Android PAN, **not an Arc7 proxy or VPN**. No root is required for
the settings-based flow, but the ROM must actually support PAN/tethering.

### Regression checks and device acceptance

```sh
make test-isolation
make test-runtime
# Stronger host media validation with an available FFmpeg binary:
FFMPEG=/path/to/ffmpeg make test-runtime
# Optionally test two native gateways concurrently after building a host TLS binary:
python3 scripts/test-app-isolation.py --native build/native/host/arc7tls
# Normal release build (includes Bluetooth Internet and the independent store transports):
./scripts/build-all.sh
make verify
```

**Validation performed on 2026-11-16:** full ARMv7/API-17 APK build and permanent-certificate
signing; `make verify` (including live TLS 1.3, signed APK/DEX/manifest/payload checks and
host-native independent gateways); Morphe HLS/remux/real-request-fixture regressions,
FFmpeg decoding and all e-reader paging checks passed. Aurora, F-Droid's injected Java and the fallback VLC transport also compile
against API 17. No Android device, Bluetooth radio or PAN internet connection was available
for hardware verification.

The offline suite checks generated manifests/permissions, stable non-colliding ports,
URI/extras/grant-preserving navigation, legacy shortcut migration, owner-scoped proxy
cleanup, F-Droid DB migration and fake Bluetooth hardware/PAN failures. Host tests do not
replace testing the actual tablet's radio, ROM permissions or Android data counters.

On the tablet: update the main APK, Home, stores and the standalone apps you use **without
uninstalling or clearing data**; disable the old suite proxy/optional VPN; open Browser and
Weather; force-stop Arc7 Freedom; confirm both apps still fetch over Wi-Fi or Bluetooth PAN
and appear separately in Settings → Apps / Data usage. Also test simultaneous apps, app
force-stop/restart, Clock alarms after reboot, file-open hand-offs and both Bluetooth
sharing directions on supported devices.

---

`v2.5.3` fixes what was reported on the tablet:

1. **The E-Reader is now a real page-turner.** Tap the **right** side of the page (or swipe left)
   for the next page, the **left** side (or swipe right) for the previous one, and the **middle**
   to hide/show the toolbar for full-screen reading. Pages are screen-sized: a whole-book EPUB that
   used to be one endless scroll now turns page by page, and turning past the end of a chapter
   rolls straight into the next one (and back again). A+ / A- and the theme buttons re-flow the
   text but keep your exact place, the position is remembered per chapter, the page counter shows
   `chapter/total · page/pages · %`, the layout uses the full 600 px screen instead of a 760 px
   column with 26 px gutters, and images/pre/tables can no longer overflow. Comic `.cbz` pages get
   the same left/right tap zones plus swipe, and the screen stays awake while reading.
2. **The Tailscale Google sign-in page now opens by itself.** Before, the tablet only drew a QR
   code and waited — and if the login link arrived after the old 12-second window it was dropped,
   so the page never appeared. Now the login link is captured for a full 30 s window, a late link is
   still picked up and opened automatically, the daemon's stdout is drained (a full pipe used to
   freeze `tailscaled`, so the URL never came out at all), `/register/` Headscale links are
   recognised, and the page opens in a real browser first (Google OAuth refuses embedded WebViews)
   with Arc7 Browser (TLS 1.3 bridge) as the fallback. There is also **Copy Login Link** and a
   **tailscaled Diagnostics** view.
3. **Every default app is a separate, installable app.** Browser, Clock, Calculator, Notes,
   Files, PDF Reader, Weather, Radio & Podcasts, Photos, Camera, Calendar, Books, Music, Terminal,
   App Manager, Package Installer, USB Internet, Wallpapers, Games, Ad Blocker, System Boost,
   Second Screen, Split View, Wikipedia, Stream Deck, Voice Recorder and Wi-Fi & VPN each ship as
   their **own signed APK** (`releases/apps/*.apk`, published as `Browser.apk`, `Clock.apk`, …).
   Each has its own package id (`ca.arc7freedom.browser`, `ca.arc7freedom.clock`, …), launcher
   icon/label, task stack, data directory and row in *Settings → Apps* — install, update or
   uninstall any of them on its own. Standalone apps retain their declared names and logos; similar
   stock apps keep their original icon. The suite keeps a forwarding/install-help `<activity-alias>` shortcut and
   `SplitAppPackageReceiver` + `SplitAppIconSync` update it immediately on standalone install/removal,
   so duplicate Camera or other twin entries do not linger until reboot.
4. **The over-the-air installer website works again.** Download buttons are plain
   `<a href>` links now — the old `onclick='onStartDl(...)'` handlers were undefined on the
   static page, so taps on a 4.2 Browser could do nothing at all. Links are root-absolute,
   there are short `/dl/<key>` URLs (e.g. `http://<pc-ip>:8000/dl/clock`) and `?file=Clock.apk`,
   a text-only `/lite` page for the stock 2013 browser, `/apps/<Name>.apk` serves the separate
   apps (falling back to the GitHub release), and the 1-click `Start-Kobo-WiFi-Server.sh/.bat`
   launchers now start serving immediately and download in the background.
5. **Alarms can now be set for specific days.** The alarm editor shows a **Sun · Mon · Tue · Wed ·
   Thu · Fri · Sat** row of check-off buttons (plus `Every day` / `Weekdays` / `Weekends` /
   `One-time` shortcuts). Any subset is saved as the alarm's day bitmask — e.g. Mon+Wed+Fri — and
   `AlarmManager` only fires on the checked days. Alarm cards show `Every Mon`, `Mon, Wed, Fri`,
   etc., and leaving every day unchecked gives a one-time alarm.
6. **F-Droid no longer crashes right after a sync.** The crash happened in the code that runs
   *after* the index has already been downloaded, verified and stored. In
   `org.fdroid.fdroid.UpdateService.onHandleIntent(Intent)` those calls —
   `notifyContentProviders()` (refresh the app list) and `performUpdateNotification()` (query
   `AppProvider.getCanUpdateUri()` with `Schema.AppMetadataTable.Cols.ALL` and build the
   "updates available" notification) — sit at bytecode offsets **648** and **678**, i.e. *after
   the method's last `try` range* (which ends at code unit `406`). Any `RuntimeException`
   thrown there (`SQLiteException` on a stale database, `IllegalArgumentException` from
   `App(Cursor)` / `checkCursorPosition()`, NPE inside `NotificationCompat`, an
   `OutOfMemoryError` from the 2026 ≈4,000-app index, …) therefore escaped the `IntentService`
   worker thread, killed the process and looked exactly like "syncs, then crashes".
   Patched **F-Droid `v1304`** now:
   * retargets both post-sync call sites (`invoke-direct/range` → `invoke-static/range`) to
     **`Arc7Bridge.safeNotifyContentProviders()` / `safePerformUpdateNotification()`**, which run
     the original private methods reflectively inside a `catch (Throwable)` — a broken refresh or
     notification step now only loses that step while F-Droid stays open;
   * appends every caught `Throwable` **plus** every uncaught crash to
     `files/arc7-last-crash.txt` (tag `Arc7CrashGuard`, mirrored to logcat) via the
     `installCrashGuard()` uncaught-exception handler installed at the first sync;
   * retypes **every `UpdateService.onHandleIntent()` catch handler to `java.lang.Throwable`**, so
     anything thrown *inside* the sync itself becomes a clean *"error updating repository"*
     status instead of a force-close;
   * declares **`android:largeHeap="true"`** in its binary `AndroidManifest.xml` (the upstream
     debug manifest ships without it), lifting the per-app Dalvik heap ceiling for index parsing;
   * hardens the `127.0.0.1:8118` TLS bridge / icon downloader so no unchecked exception
     (`ClassCastException`, bad URL scheme, …) can escape into UIL or F-Droid.

Since `v2.5.0` (still included):

1. **YouTube & YouTube Music media pipeline.** Metadata can load even when no media URL
   is usable. The current binder handles H.264/AAC progressive MP4, separate DASH tracks,
   MPEG-TS/AAC HLS and finite fMP4 HLS independently, probes media bytes and builds classic
   non-fragmented MP4/M4A for Android 4.2. Unknown output lengths wait for full preparation
   rather than guessing a `Content-Length` from input downloads. Foreground metadata
   enrichment is asynchronous and client attempts are bounded. See the maintenance notes
   above for current request profiles and the explicit live/device-test limitation; the
   original blanket claim of working 2026 streams was not established by host tests.
2. **Custom apps are genuinely separate applications.** `YouTube` and `YouTube Music` are now
   their **own installable APKs with their own package ids** — `ca.arc7freedom.youtube`
   (`releases/YouTube.apk`) and `ca.arc7freedom.ytmusic` (`releases/YouTubeMusic.apk`). Each has
   its own launcher icon/label, task stack, data directory and background services, appears
   separately under *Settings → Apps*, can be updated or uninstalled on its own, and cross-launches
   the other app. The suite no longer shows duplicate YouTube / YouTube Music icons; the 1-Tap
   Upgrader and the silent companion updater install and refresh both standalone APKs.

Everything from `v2.4.1` and earlier is still included: Launcher `v55` with effortless widget
removal, owned-player Now Playing controls + silent external Clock Standby metadata, Official Tailscale Google sign-in,
patched F-Droid `v1304`, bundled VLC, Aurora Store, `python3` + `apt`, 120 pre-downloaded 2026
Root CAs, the zero-SSL local Wi-Fi installer portal and the full app suite.

## App Manager & Repository Layout

- **Uninstall from App Manager:** open an app's **Manage** menu and select **Uninstall App**;
  Android's own Package Installer asks for final confirmation. User-installed apps and updated
  system apps are supported. The running App Manager itself, core Android services, the package
  installer, device drivers, and the only enabled Home launcher are protected. Preinstalled system
  apps can still be safely frozen or unfrozen, but their system-image APKs are not deleted.
- **Consistent Android module layout:** each installable project uses the standard
  `src/main/AndroidManifest.xml`, `src/main/java/`, and `src/main/res/` structure:

  ```text
  app/                 main Arc7 Freedom application and shared app code
  launcher-android4/   Home launcher
  aurora-store/        Aurora Store client
  vlc-player/          local VLC-compatible player fallback
  upgrader/            1-tap system updater
  split-apps/          generated per-app manifests + apps.tsv build index
  native/              ARMv7 native source
  scripts/             build, generation, patching, and verification tools
  tools/               user-facing host utilities and installer servers
  releases/            intentionally tracked release APKs, portal, and host tools
  ```

  All five full Android modules are declared in `settings.gradle`. `scripts/build-all.sh`
  remains the reproducible signed release build; it reads from these standard module paths and
  writes release outputs to `releases/`.

---

## Published `v2.5.3` downloads (historical GitHub release / main-branch links)

**These published links have not been refreshed with this branch's maintenance build.**
Use the local `releases/` files and install checklist above for the current repairs.

All APKs are `zipalign`ed, **Android 4.2 Dalvik (`DEX 035`) + APK Signature Scheme v1 (JAR) / v2 / v3**
signed with the permanent release certificate
[`keystore/arc7freedom-release.jks`](keystore/arc7freedom-release.jks)
(`SHA256: 11:FD:17:ED:80:C9:13:EF:CB:F9:8F:76:73:DA:00:90:42:E7:64:7E:44:E5:D6:C5:F4:2F:CA:A5:CE:4C:D1:0F`):

- **Latest GitHub Release (`v2.5.3`):** **[https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest)**
- **Direct APK & Tool Streams:**
  - **[`Upgrade-My-Kobo-To-v2.5.3.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/Upgrade-My-Kobo-To-v2.5.3.apk)** — 1-Tap OTA Root & Full `v2.5.3` Upgrader (`versionCode=252`; installs `Arc7 Freedom` `v2.5.3`, the separate `YouTube` & `YouTube Music` apps, Launcher `v55`, F-Droid `v1304`, Tailscale Google Auth, PC Stream Deck, VLC, Aurora Store, `python3`, `apt` & 118 2026 CAs)
  - **[`KoboArc7Freedom.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/KoboArc7Freedom.apk)** — Main Rooting & Daily-Driver Suite (`Arc7 Freedom` `v2.5.3`, `versionCode=252`)
  - **[`YouTube.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/YouTube.apk)** — **Separate** `YouTube` app (`ca.arc7freedom.youtube`, `versionCode=252`): 2026 DASH remux playback, SponsorBlock, Return YouTube Dislike, comments, background audio & offline downloads
  - **[`YouTubeMusic.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/YouTubeMusic.apk)** — **Separate** `YouTube Music` app (`ca.arc7freedom.ytmusic`, `versionCode=252`): AMOLED player, screen-off AAC background playback, synced lyrics, queue/shuffle/repeat, equalizer & offline `.m4a`
  - **[`VLC.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/VLC.apk)** — Standalone Android 4.2 ARMv7 **VLC Media Player** (`org.videolan.vlc`, also pre-bundled inside `KoboArc7Freedom.apk`)
  - **[`AuroraStore.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/AuroraStore.apk)** — Android 4.2 Play/FOSS store client with binary APK validator & auto `SHA1withRSA` JAR `v1` signer (`v4.8.5`)
  - **[`FDroidClassicLegacy.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/FDroidClassicLegacy.apk)** — Patched F-Droid Classic (`v1304`, largeHeap + Throwable-catch sync stability) with the complete Android 4.2 `SHA-256` `Incorrect signature` fix, BER/DER PKCS#7 verifier, non-colliding certs & TLS 1.3 bridge
  - **[`VanillaAndroid4Home.apk`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases/VanillaAndroid4Home.apk)**
- **Separately installable default apps (`v2.5.3`, 27 signed APKs, one package each):** [`Ad-Blocker.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Ad-Blocker.apk) · [`App-Manager.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/App-Manager.apk) · [`Books.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Books.apk) · [`Browser.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Browser.apk) · [`Calculator.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Calculator.apk) · [`Calendar.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Calendar.apk) · [`Camera.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Camera.apk) · [`Clock.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Clock.apk) · [`Files.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Files.apk) · [`Games.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Games.apk) · [`Music.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Music.apk) · [`Notes.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Notes.apk) · [`Package-Installer.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Package-Installer.apk) · [`PDF-Reader.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/PDF-Reader.apk) · [`Photos.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Photos.apk) · [`Radio-Podcasts.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Radio-Podcasts.apk) · [`Second-Screen.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Second-Screen.apk) · [`Split-View.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Split-View.apk) · [`Stream-Deck.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Stream-Deck.apk) · [`System-Boost.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/System-Boost.apk) · [`Terminal.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Terminal.apk) · [`USB-Internet.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/USB-Internet.apk) · [`Voice-Recorder.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Voice-Recorder.apk) · [`Wallpapers.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Wallpapers.apk) · [`Weather.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Weather.apk) · [`Wi-Fi-VPN.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Wi-Fi-VPN.apk) · [`Wikipedia.apk`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Wikipedia.apk)
  — or open the OTA portal (`Start-Kobo-WiFi-Server.bat/.sh`) and tap the ones you want. — Custom-skinned **Home** launcher (`v55`) with effortless widget removal, drag-and-drop icons, multi-page desktops, multi-edge app bar, 5 skins & 44 high-DPI icons

---

## What's New in `v2.5.3`

1. **Per-day alarm check-off (`Arc7ClockActivity` + `Arc7ClockStore`):**
   - The `Repeat` cycle button is gone; the alarm editor now has seven toggle buttons
     (`Sun … Sat`) that light up when checked, a live `Repeat: Mon, Wed, Fri` summary, and
     one-tap presets (`Every day`, `Weekdays`, `Weekends`, `One-time`).
   - `repeatMask` (bit 0 = Sunday … bit 6 = Saturday) is persisted exactly as checked, so
     `computeNextTriggerMillis()` / `AlarmManager.set(RTC_WAKEUP, …)` already honour any subset.
   - `Arc7ClockStore.describeRepeatMask()` / `describeAlarmDays()` render single-day alarms as
     `Every Mon` and the alarm list keeps showing the full day summary.
2. **F-Droid `v1304` sync stability:** the two post-sync calls of
   `UpdateService.onHandleIntent()` (`notifyContentProviders()` @648, `performUpdateNotification()`
   @678 — both outside every `try` range) are retargeted to
   `Arc7Bridge.safeNotifyContentProviders()` / `safePerformUpdateNotification()` reflective
   `Throwable` guards, so a failing refresh/notification no longer force-closes the app; all
   in-sync handlers are retyped `Exception → Throwable`, the binary manifest gains
   `largeHeap="true"`, an `Arc7CrashGuard` uncaught-exception handler appends every stack trace to
   `files/arc7-last-crash.txt`, and `Arc7Bridge`/`BaseImageDownloader` reject bad URLs/types
   instead of throwing. Version code bumped `1303 → 1304` so the new build upgrades in place.

## What's New in `v2.5.0` (still included)

1. **`YouTube` media pipeline (`MorpheRemuxer` + `MorpheStreamServer` + `MorpheEngine`):**
   - Empty-`formats` DASH handling, video-only `avc1` + audio-only `mp4a` merge into seekable
     classic MP4, exact-output-length local `206` server and H.264/AAC ≤720p/30fps filtering
     for the MT8125 decoder, quality selection and error reporting, plus the
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
     install help when the separate package is absent (no hidden in-suite copy).
4. **Verification:** new `verify_v250_streaming_and_standalone_apps()` gate (package ids, labels,
   launcher filters, v1+v2+v3 signatures, embedded CA bundle + TLS engine, 2026 InnerTube strings,
   upgrader nesting) and a host-JVM suite `scripts/test-morphe-remux.py` that proves the remuxer
   and the strict Range/`206` server byte-for-byte.

---

## What's New in `v2.4.0`

1. **`YouTube` App — Morphe Edition (`YouTubeActivity` + `MorpheEngine`):**
   - Introduced the local Range/TLS stream proxy, hardware H.264/AAC playback path, SponsorBlock,
     dislike counts, channels, comments and offline downloads. Original client versions in
     this historical release are not a guarantee of current service access; use the
     maintenance profiles/pipeline and testing caveats above.
2. **New Standalone `YouTube Music` Morphe App (`YouTubeMusicActivity`):**
   - Dedicated launcher app with AMOLED dark UI, song/album/artist/playlist search & quick picks, `128–160 kbps` M4A screen-off background playback (`PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_HIGH_PERF`), SponsorBlock non-music skip, synced LRC lyrics, queue/shuffle/repeat, 5-band `Equalizer` + `BassBoost`, and offline `.m4a` downloads.
3. **Effortless Home Screen Widget Removal (`VanillaAndroid4Home v54`):**
   - Custom `InterceptingWidgetHostView.onInterceptTouchEvent` intercepts `420ms` long-presses before clickable child `RemoteViews` swallow them, **`Rearrange`** mode highlights every widget with 1-tap **`✕ Remove`** + **`↕ Size`** badges, and Home long-press shows active page widgets right at the top with 1-tap **`✕ Remove`** (`AppWidgetHost.deleteAppWidgetId` + persisted removal).
4. **System-Wide Audio Notification + Live Clock Standby Display (`NowPlayingManager` + `Arc7AudioService` + `Arc7DeskClockActivity`):**
   - Prepared Arc7 players post their own compact/expanded controls. External players such as
     **VLC** keep their official notifications; observers only feed metadata to **Clock
     Standby** and widgets. The old duplicate system-wide observer notification is removed.
5. **Clean System App Naming (`Arc7 Freedom` Only):**
   - Only **`Arc7 Freedom`** carries `"Arc7"` in its name — every other built-in app, widget, launcher, store, and utility uses clean human names (`YouTube`, `YouTube Music`, `Browser`, `Clock`, `Books`, `Music`, `Calendar`, `Notes`, `Files`, `Photos`, `Camera`, `Calculator`, `Weather`, `PDF Reader`, `Radio & Podcasts`, `Voice Recorder`, `Games`, `Stream Deck`, `Wi-Fi & VPN`, `Home`, `Aurora Store`, etc.).
6. **Official Tailscale Auth with Google Sign-In (`TailscaleManager` + `QrCodeEncoder` + `Arc7VpnActivity`):**
   - Official Linux `3.4.5`-safe `tailscale_1.78.1_arm.tgz` + 100% standards-compliant **ISO/IEC 18004 Reed-Solomon QR code** for real `https://login.tailscale.com/a/...` Google OAuth sessions + 1-tap on-tablet Google Sign-In + live `BackendState` polling that activates `Arc7VpnService` automatically as soon as Google login finishes.
- **PC Stream Deck, School VPN Relay, USB & Home Wi-Fi Companion Scripts:**
  - **Windows / macOS / Linux PC Stream Deck Server + Live Web Studio (`:18991` / `:18995`):** USB ADB uses a separate host bridge (`:18992` → Kobo `:18991`) so it cannot collide with the Wi-Fi listener. **[`Start-PC-Stream-Deck.py`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/tools/Start-PC-Stream-Deck.py)** | **[`Start-PC-Stream-Deck.bat`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/tools/Start-PC-Stream-Deck.bat)** | **[`Start-PC-Stream-Deck.sh`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/tools/Start-PC-Stream-Deck.sh)**
  - **Personal Port-443 TLS 1.3 Stealth School VPN Home Relay:** **[`Start-PC-School-VPN-Relay.py`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/tools/Start-PC-School-VPN-Relay.py)**
  - **Chromebook / PC / Mac Extended `1280×800` Second Screen & Macro Deck Sender:** **[`Start-Kobo-Second-Screen.py`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/tools/Start-Kobo-Second-Screen.py)**
  - **Chromebook / Linux Wired USB Internet Bridge:** **[`Start-Chromebook-Wired-Internet.sh`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/tools/Start-Chromebook-Wired-Internet.sh)**
  - **Windows 10 / 11 Home Wi-Fi Rooting Server:** **[`Start-Kobo-WiFi-Server.bat`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/tools/Start-Kobo-WiFi-Server.bat)**
  - **macOS / Linux Home Wi-Fi Rooting Server:** **[`Start-Kobo-WiFi-Server.sh`](https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/tools/Start-Kobo-WiFi-Server.sh)**

---

## What's New in `v2.3.1` & `v2.3.0`

0. **Complete Hardware Fix for F-Droid `Error during update: Incorrect signature` (`v2.3.1` / `FDroidClassicLegacy v1303`):**
   - Eliminates Android `4.2.2` Apache Harmony `JarUtils.verifySignature()` throwing `SecurityException("Incorrect signature")` on `SHA-256` / `SHA256withRSA` repository `index-v1.jar` files (`IndexV1Updater.processDownloadedIndex` `new JarFile(indexFile, false)`) and installed APKs (`App.initInstalledApk` -> `Arc7Bridge.populateInstalledApkInfo`), verifying 100% of repos through our pure-Java `BigInteger.modPow` PKCS#1 v1.5 RSA (`1024–8192`-bit) + ITU-T X.690 BER indefinite-length (`0x80`) + DER + RFC 5652 CMS `SignedAttributes` PKCS#7 verifier!

1. **Strict School Wi-Fi Rescue & Network Policy Bypass (`Arc7 Wi-Fi & VPN`):**
   - Fixes strict school Wi-Fi not showing up or refusing to connect on the Kobo Arc 7's 2.4 GHz `MT6628` radio + Android `4.2.2` stack via raw `WifiManager` + root `wpa_cli` air scan diagnostics (detecting Hidden SSIDs, `802.1X` WPA2-Enterprise / `eduroam` APs, Channel `12–13` regulatory lockouts, WPA3-Transition APs, and 5 GHz-only APs), 1-tap `PEAP`/`TTLS`/`MSCHAPv2`/`eduroam` + Hidden SSID configurator, Wi-Fi MAC Randomizer, TLS 1.3 Captive Portal Login Browser, School HTTP Proxy configurator, Port-443 Encrypted DNS-over-HTTPS (`DoH`), and Chromebook USB / 2.4 GHz Hotspot rescue!
2. **Touch Drag-and-Drop App Icon Rearrangement (`VanillaAndroid4Home v53`):**
   - Long-press & drag (or tap **`Rearrange`** on the Home Top Bar) to move any app icon freely into **any grid slot on any desktop page**, **swap positions**, **drop onto another icon to create/add to a Folder**, **drag across multi-page edges**, **drop onto the Top Action Bar** (*Unpin / Rename / Folder / App Info / Uninstall*), **swap App Bar slots**, and **custom-sort the All Apps drawer**!
3. **Full `1280×800` LCD Stream Deck for Windows / macOS / Linux (`Arc7 Stream Deck` + `Start-PC-Stream-Deck.{py,bat,sh}`):**
   - `15` (`5×3`), `24` (`6×4`), `32` (`8×4` XL), and `6` (`3×2` Mega) glowing LCD key layouts over **Wi-Fi LAN**, **USB ADB** (host `:18992` → Kobo `:18991`), or **Tailscale IP**, featuring **OBS WebSocket v5 (`:4455`)** scene/record/stream/mute controls, **Media & Touch Volume Faders**, **Momentary Push-to-Talk**, **Live PC CPU / RAM / Volume telemetry**, and **Live PC Browser Deck Studio (`:18995`)**!
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
4. **Live Radio-Browser, Podcast `.mp3` Downloader & Offline RSS Reader (`Radio & Podcasts`):**
   - Top-voted and searched stations come from the Radio-Browser directory (`radio-browser.info`), with HTTPS streams relayed through the TLS 1.3 gateway for screen-off playback; includes Podcast search/downloads and an RSS 2.0 / Atom offline reader.
5. **Studio 44.1 kHz Waveform Voice Recorder & Lossless WAV Trimmer (`Arc7 Recorder`):**
   - 16-bit PCM `.wav` recording with Android 4.2 Pause/Resume, live 60 fps waveform, and lossless `.wav` sample trimming.
6. **Zero-Ad 60 fps Offline Classic Games Suite (`Arc7 Arcade`):**
   - **Chess vs 2-Ply AI**, **2048**, **Sudoku**, **Minesweeper**, and **Klondike Solitaire**.
7. **System-Wide `/etc/hosts` Ad/Tracker Blocker & Per-App Root Firewall (`Arc7 Shield`):**
   - Pure `mksh`/`toolbox` hosts adblocker + live `StevenBlack/hosts` sync over TLS 1.3 + per-app UID `iptables` firewall.
8. **`+384 MB` Kernel `zRAM`/Swap Expander, Quad-Core CPU Profiles & Battery Doctor (`Arc7 Boost`).**
9. **Chromebook / PC / Mac Extended `1280×800` USB/Wi-Fi Second Screen + 12-Key Macro Deck (`Arc7 Second Screen` + `Start-Kobo-Second-Screen.py`).** The screen stream stays connected without Linux `xdotool`; install `xdotool` to enable PC clicks and keyboard macros.
10. **Landscape Dual-Pane Split-Screen Multitasking (`Arc7 Split View` — Web, Books/PDF, Notes, Dict/Wiki & Calculator side-by-side).**
11. **Upgraded `VanillaAndroid4Home v52` (`3–5` Multi-Page Desktops, `4–7` Column Grid Density, Desktop & App Bar `@folder:` App Folders, 4-Digit App PIN Lock, and JSON Layout Backup/Restore).**
12. **Wireless WebDAV + HTML5 Network Drive (`:8085`) & Remote LAN Share Browser with Direct `VLC` Streaming (`Arc7 Files`).**
13. **Offline English Lexicon + `Wiktionary` & Clean Reader `Wikipedia` with Offline Saved Articles (`Arc7 Dict & Wiki`).**
14. **Automatic Sunset-to-Sunrise Night Light (`20:00–07:00`), Auto-Charging Smart Bedside Dock & Smooth 30s Fade-Out Audio Sleep Timer.**
