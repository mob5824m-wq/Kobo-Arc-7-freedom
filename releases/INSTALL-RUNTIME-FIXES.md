# Install all maintenance patches — v2.6.8

Local signed build: suite/default apps/upgrader **2.6.8 / 268**; patched F-Droid **1306**; Home **5.6.0 / 56**. Use this build/local Wi-Fi portal or the matching **v2.6.8** release after the main-branch workflow succeeds, not an older “latest” asset. The version is carried in the APK metadata, not just the workflow label.

## One full bundle, every install method

Install either:

- [KoboArc7Freedom.apk](KoboArc7Freedom.apk), then **open Arc7 Freedom once**; or
- [Upgrade My Kobo v2.6.8](Upgrade-My-Kobo-To-v2.6.8.apk), which installs the suite in place and opens its normal main screen.

In Arc7 Freedom, open **PATCHES → Apply / Retry All Patches**.

The same restartable maintenance service is used after **direct APK, microSD, Wi-Fi/OTA, USB/ADB, backup-tool, and upgrader installs**, and on suite startup, package replacement, boot/network reconnect, successful rooting and in-place Android conversion. Android may keep a newly sideloaded app stopped until it has been opened, so do not skip that first launch.

The generated **Clean Android ROM ZIP** also carries the current complete suite and starts maintenance after boot. An arbitrary third-party ROM cannot be assumed to preserve these apps or patches: reinstall the full suite and open the patch screen after flashing it. Neither the ROM ZIP nor physical flashing has been device-tested here.

### What runs

- Content-aware, data-preserving updates for **38 bundled APKs**: Home, patched F-Droid, Aurora, VLC, YouTube, YouTube Music, and **all 32 separately packaged default apps**. Clock, Music, Radio, Podcasts, Voice Recorder and Package Installer are no longer omitted. **QR Transfer** now has its own offline package; it replaces the retired Bluetooth Internet catalog entry. Already installed Bluetooth Internet copies are not silently uninstalled.
- Installed APK **SHA-256 read-back**, including same-version maintenance builds. Exact copies are skipped; newer installed versions are retained rather than downgraded.
- Retryable removal of **only** obsolete Arc7 `localhost:8118` / `127.0.0.1:8118` global/saved-Wi-Fi proxies. Unrelated proxies, VPNs and USB routes are preserved.
- Bundled per-app CA/TLS assets; backed-up, verified offline system CA installation with root; Python/apt/TLS command-line payload repair; Home/Recents provisioning checks.
- Previously enabled storage bindings and saved schedules are restored. Network-dependent time/timezone/chime work is attempted separately and reported as deferred/unavailable when offline; it is not proof of installed APK patches or live internet access.

The default APKs are stored as deduplicated **byte recipes**, to keep the full offline installer small. Extraction reconstructs the original signed APK exactly, verifies its SHA-256 and package/version, and does not re-sign it on the tablet. Installed apps still have independent UIDs, data, tasks and networking. No shared suite runtime or universal proxy was introduced.

### Rooted tablet

The service applies normal **`pm install -r` in-place updates** and verifies the installed bytes. Read the report for each `CURRENT`, `UPDATED`, `NEWER_KEPT`, `PENDING` or `BLOCKED` result. A failure in one app does not prevent attempts for the other apps. Retry after resolving storage/permission errors.

### Unrooted tablet

Tap **Install pending apps (Android confirmations)**. Android confirms **each separate APK**; a single APK cannot silently install or patch other UIDs without root. The queue advances only after installed bytes have been verified. Cancelling/failing a confirmation pauses the queue safely; tap again to resume. No installer dialogs appear automatically at boot.

System/root-only steps remain **pending**, not falsely marked complete. Rooting is still a separate explicit action. Embedded per-app TLS does not require root.

### Data safety and optional changes

Back up important data and stop playback before updates. A signature mismatch, failed update or insufficient storage **never triggers an automatic uninstall, `pm clear`, deletion of the existing suite/Home system APK, or clean reinstall**. A key mismatch stays blocked; decide on a migration only after a backup. Explicit Clean Reinstall remains a separately confirmed, data-destructive operation in Package Installer.

Maintenance **does not** automatically flash boot/recovery images, wipe data, convert the OS, remove stock/Kobo apps, enable ad blocking, change storage policy, turn on VPN/tethering, or require all traffic to pass through the suite. Those optional tools retain their own explicit controls. In-place conversion still performs its separately confirmed conversion/removal steps, and only proceeds after the replacement Home is verified.

Individual [default-app APK downloads](apps/) update **only that app**. Use the full suite/upgrader if you want the complete patch set applied.

## F-Droid catalog and earlier crash repairs

F-Droid **1306** adds the pinned official F-Droid/Guardian archives and defaults the
available-app catalog to **All**, including a one-time category reset on migration.
An empty catalog or updates list is not proof that all repository apps are installed.
Existing keys, credentials, custom repositories and installed-state joins are retained;
failed migrations stay retryable. After updating, sync the repositories and check the
actual installed apps separately. See [2.6 features](SUITE-FEATURES.md).

PC USB/backup scripts and the upgrader no longer force a patch-management screen.
Use the **PATCHES** tab when you want to inspect/retry work. Rootless confirmation
queues pause when you leave the tab/app; they do not pop up over another app.


F-Droid **1306** fixes `java.util.ArrayList cannot be cast to java.lang.String[]` in the injected streaming index parser. Untyped JSON arrays can be `ArrayList`, not `String[]`. Repository mirrors **and every push-request value** are now normalized to actual string arrays; existing arrays and null/empty values work too. Malformed types produce a checked parse error instead of that cast failure.

[FDroidClassicLegacy.apk](FDroidClassicLegacy.apk) can also be installed on its own as an in-place update. Keep repository pins, preferences and databases; do **not** clear data to repair this exception. The full bundle installs the same pre-patched signed APK offline—no runtime patch download is needed.

## Expanded Weather and earlier repairs retained

- The full bundle also includes the [expanded Weather app](WEATHER-FEATURES.md): 48-hour / 14-day forecasts, saved cities, optional one-time location, air quality, official CA/US alerts, past radar and a refreshing widget. The standalone Weather APK updates only Weather; it does not change the earlier repair requirements.
- VLC retains its own media notification. Arc7 observers update Clock/widgets silently, rather than creating duplicate controls. The full bundle now updates all old standalone observer APKs too.
- Home's App Info/uninstall targets the actual owning package. Package Installer is `ca.arc7freedom.packageinstaller`; the updater is `ca.arc7freedom.upgrader`, with its own App Info button.
- YouTube/Music announce Playing only after prepare/start. Finite compatible HLS/DASH media can be prepared as classic MP4/M4A through each owning app's TLS engine. Metadata resolution alone is not playback success.
- YouTube Music's Android-4 **`MediaPlayer -38/0`** repair prevents duration/position/transport/DSP polling before preparation and serializes native access with release. Cleanup releases directly instead of issuing invalid `stop()` calls in Preparing/Error; genuine AAC/ADTS container recovery remains bounded to one retry. See [Music state-error diagnosis and validation](GLASS-MUSIC-QR.md#youtube-music-mediaplayer--380-during-preparation); `make test-audio-state` exercises the production service with asynchronous-error recording fakes, not a real decoder/tablet.

## Checks and validation boundaries

The build/test gates cover API-17 compilation, ARMv7, DEX 035, permanent-certificate v1/v2/v3 signatures, exact reconstruction of all 38 signed payloads, embedded-upgrader equality and release checksums. `make test-install` runs the actual generated F-Droid bridge and installer code on a host JVM, with mirrors/requests as lists and arrays, same-version repairs, newer/key-conflicting apps, corrupt payloads, false `pm Success`, failed updates, rootless confirmation status, retry idempotence and real USB/backup shell helpers using fake ADB. The Linux gate checks Windows launcher/helper/package bytes; the separate **Native Windows launchers** CI job executes all six real `.bat` paths in cmd + Windows PowerShell 5.1 with fake ADB/Python and local HTTP/backup fixtures. See [Windows setup](../tools/WINDOWS-TOOLS.md); extract the matching ZIP rather than mixing builds.

`make verify` also runs the existing offline media, routing, proxy, launcher and reader regression gates, plus synthetic-camera/native/browser QR interoperability and recorded Liquid Glass material tests. These are **not** physical-device results. No Kobo, live YouTube playback, ROM flash, real Android PackageInstaller confirmation sequence, physical Windows-to-Kobo USB, camera or GPU was available to validate here. Root access, ROM permissions, free flash space, hardware codecs and current YouTube endpoints can still limit operation; use the visible patch report and device tests before relying on it.

Checksums: [SHA256SUMS.txt](SHA256SUMS.txt). Default APKs and deduplicated assets are regenerated by the build and intentionally excluded from Git; the signed suite/upgrader contain the entire offline payload.
