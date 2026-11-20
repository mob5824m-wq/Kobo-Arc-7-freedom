# Liquid-glass-inspired UI, Music format recovery & offline optical file transfer

Branch build for Kobo Arc 7 / Arc 7HD, Android 4.0.3–4.2.2 (API15 minimum / API17 target).
Versions and permanent signing identity are retained. This work does **not** publish a
new public release or change your installed app data automatically.

## Install in place

- Install this build's `VanillaAndroid4Home.apk` for the new Home dock/top capsule.
- Install `YouTubeMusic.apk` for the Music playback repair. Stop any existing playback
  before updating. Do **not** uninstall or clear data.
- Install the generated `apps/QR-Transfer.apk` for the independent Kobo companion (built-in
  storage browser, optional system picker/Share, send/receive hub, densities, saved receive
  management); `apps/Files.apk` retains its existing optical send/receive actions. The full `KoboArc7Freedom.apk` / 2.6.5 upgrader also bundles the same signed
  Home, Music and Files payloads; use **PATCHES → Apply / Retry All Patches** and confirm
  any pending Android install prompts when not rooted.
- Default app APKs are generated/ignored by Git, available in the build artifact and
  local installer portal. The committed suite/upgrader embed their exact signed bytes.
- Check `SHA256SUMS.txt` for these exact build files. An older public “latest” asset may
  not include this work, even though it has the same version number.

## Glass: what changed, and what Android 4 can actually render

The default glass material is now **Apple-Liquid-Glass-inspired**, rather than an almost
opaque gradient: a lower-opacity tinted body, three-step contact shadow, soft sheen, broad
Fresnel thickness, opposing bright/dim specular rims, a subtle cyan/pink prismatic edge,
a lower caustic and a pressed highlight. Headers float as inset capsules; icon tiles use
moulded translucent edges rather than flat sticker gradients. Legacy Browser, Clock,
Audio, Terminal, App Manager, suite/upgrader and Aurora controls now share the material too. Buttons and the floating
Home dock use larger capsule curves. Shared cards, elevated surfaces, buttons and headers
use the material, and Music's search/player/list surfaces now use it too.

Home's glass dock and top capsule show a **small, cached, blurred wallpaper sample**, aligned
to their screen position, with separate centre (1.018×) and stronger edge (1.075×)
magnification to suggest a refractive lens. The sample is at most 160 pixels on its longest edge; it is not
recaptured during drawing or animated each frame. Wallpaper/skin/resume changes refresh
it. The clear-opacity override removes the body and frost while retaining the lens rim.
Solid, neon, full-width and minimal Home alternatives remain available. Music keeps a dark player palette with white
transport typography even when the OS daylight skin is selected.

This is not Apple's proprietary renderer and **not live refraction/backdrop blur of moving
app content**. Wallpaper-edge refraction is cached optical sampling, not a live scene capture. Android 4 has no modern RenderEffect pipeline. The implementation uses
API15-compatible Canvas, gradients, alpha and a bounded wallpaper-only blur, without
screen capture, an always-running effect loop, API21 elevation or hardware clipPath calls.
App pages use static ambient colour to make the transmitted background visible.

## YouTube Music: `MediaPlayer -38/0` during preparation

`-38` is an invalid player operation/state, **not evidence of an unsupported stream**.
The Music screen polls duration/position every 600 ms, including while `prepareAsync()`
is pending. Android 4.2's native `getDuration()` rejects that state and its JNI bridge
posts `OnError(-38, 0)` asynchronously; catching a Java exception cannot prevent it.
The service also used to call `stop()` when releasing an unprepared/failed player,
which is another invalid operation on this Android version.

This build keeps duration/position/playing queries, seeks, bookmarks, SponsorBlock,
sleep-volume and EQ work behind prepared-state guards. Queries return zero/false
while loading, and transport controls do not touch the preparing native player.
Native access and readiness changes use the same service monitor as replacement/release.
The guard is cleared on errors; cleanup detaches listeners and releases directly,
without calling `stop()` in Preparing/Error. Already-queued callbacks still have to
match the active player/request, and a late compatibility URL is cancelled after Next/Stop.

A compiled-production-service regression reproduced the exact **“MediaPlayer -38/0 —
The device could not play this stream”** failure before the fix, using a recording
MediaPlayer that **posts an error instead of throwing**. It now checks quiet pending
polling/controls, normal prepared/paused/completed transport, sleep/SponsorBlock/EQ,
stale callbacks, timeout, concurrent polling/release and the bounded compatibility retry.
Run `make test-audio-state`; the full `make verify`/CI runs it too. A remaining `-38/0`
is described as a player-state error and is **not** blindly retried as a container failure.

This establishes a reproducible service bug and its regression repair, **not proof of
the exact path taken by the physical Kobo**. Update `YouTubeMusic.apk` in place and
retest the failing track on the tablet; do not uninstall/clear data. The AAC-LC/container
repairs below are preserved. Android-4 native references:
[`getDuration_l`/`stop`](https://github.com/LineageOS/android_frameworks_av/blob/cm-10.1/media/libmedia/mediaplayer.cpp)
and [`process_media_player_call`/`getDuration` JNI](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-4.2.2_r1/media/jni/android_media_MediaPlayer.cpp).

## YouTube Music: the `MediaPlayer 1/-1010` path

Previously the shared binder tried HLS first, even for Music, and an `mp4a` label or a tiny
initialization probe was enough to register audio as playable. That does not establish that
the bytes describe an Android-4-compatible AAC stream, nor that the ROM accepts its container.
The exact cause of the user's device error cannot be proven without device diagnostics.
The repair now addresses both of these failure paths:

1. For Music, try up to three adaptive audio candidates **before HLS/video**. Parse the
   actual `stsd/mp4a/esds` AudioSpecificConfig, not only a server MIME/codec string.
2. Require clear AAC-LC (object type 2), mono/stereo, supported indexed sample rates at
   or below 48 kHz and standard 1024-sample framing. Reject HE-AAC/SBR/xHE, encrypted
   entries, malformed descriptors and unsupported channel/rate/frame modes.
3. Normalize MP4 sample-entry channel count, sample size and rate from the authoritative
   AAC configuration, then prepare an **audio-only classic M4A**. No video is downloaded
   for this path. HLS remains a fallback if no adaptive audio candidate is usable.
4. If the service receives a supported container/decoder failure (`1/-1010`, `1/-1007`
   or the vendor unknown-container code), it can retry **once** as AAC-LC in **ADTS**.
   This copies the same AAC access units unchanged and supplies ADTS headers, avoiding
   the ROM's MP4/HLS extractor. It is not a transcoder.
5. Recovery is tied to the queue request and resolved track, cancels the previous player,
   never advances the queue to hide an error, and cancels a late alternate stream after
   Next/Stop. Another failure is reported; it does not retry forever.

The prepared-audio guard, actual-player notification ownership and earlier YouTube audio
repair remain. No raw fragmented audio or silent-video URL is substituted. Incompatible
provider codecs, missing compatible tracks, authentication/bot restrictions, network errors
and hardware decoder defects can still prevent playback. Finite preparation needs cache
space and can take time. **No live-provider or physical Kobo playback guarantee is made.**

## Optical transfer: actual file bytes, no shared network

This is separate from **QR link · LAN share**. That older option opens a temporary browser
share over trusted Wi-Fi. The new optical mode puts the file's actual bytes inside a
repeating sequence of QR codes and reads them with the receiving camera.

### Kobo → another device

1. Open the independent **QR Transfer** app → **Choose file** (built-in Kobo storage browser,
   optional other-app picker or Share to QR Transfer) → **Show file-byte QR codes**. Or in
   **Files**, long-press a file → **Send file bytes via QR (offline)**. ZIP folders first.
   The limit is **64 MiB** (legacy mode: 1 MiB). Small documents still work best.
2. On the other Kobo, open **QR Transfer → Receive** or **Files → Receive QR bytes**. On a computer/phone, save and
   open the self-contained [`qr-transfer.html`](../tools/qr-transfer.html) and start its
   receiving camera. No separate QR scanner or BarcodeDetector API is needed.
3. Point the camera at the codes and accept the file name/size. Keep both devices awake
   and steady; tap the Kobo preview to focus or try **Switch camera / retry**. Front-only
   cameras are supported. Scan until all frame numbers have arrived.
4. The receiver checks the full SHA-256 and then offers **Save verified file**. Kobo files
   go to `/sdcard/Download/QR Received/`. Existing files are never overwritten; a unique
   numbered name is reserved and the completed file is published atomically.

### Computer/phone → Kobo

1. Save `tools/qr-transfer.html` and open it in a current browser. The file includes its
   QR encoder/decoder; it does not fetch libraries or upload anything.
2. Select a file under **Send bytes** and point **Files → Receive QR bytes** on the Kobo
   at that screen. Accept the header, receive the frames, then save after verification.
3. A browser needs Web Crypto for SHA-256. Browser camera access requires a secure
   context (HTTPS/localhost; direct local files work in some browsers). If camera access
   is blocked, it can still display codes for the Kobo camera from a local saved file.
   The page explains the limitation instead of replacing byte transfer with a URL.

### Progress, replay and limits

- The header repeats every eight data frames and at the start of each loop. Repeated and
  out-of-order frames are safe. Different transfers and conflicting duplicate frames are
  rejected. Missing frame numbers are shown (one-based).
- Pause the sender and use **Go to frame / Replay frame** to replay a missing frame;
  `0` displays the header. Previous/Next and 0.35 / 0.7 / 1.2-second speeds are available.
- **Easy focus: 128 bytes/QR**, **Standard: 512 bytes/QR** (default), **Dense: 1024 bytes/QR**
  (requires a sharp camera/sufficient screen size). Profiles work up to 64 MiB. **Legacy v1**
  sends 128-byte frames with the old 1 MiB limit; new receivers accept both protocol versions.
- At normal 0.7-second speed, one clean pass of 1 MiB takes about 108 / 27 / 13.5 minutes
  for Easy / Standard / Dense, including repeated headers. Standard 64 MiB can take over a
  day. Missed frames and QR rendering/decoding time add to these estimates. Use LAN share for speed.
- Android senders stream to an immutable private snapshot; native receivers use bounded
  buffers/bitmaps and disk partials, not a 64 MiB Java heap array. Receiver data is synced
  before an atomic bitmap checkpoint every 32 new frames, on completion and on normal close.
  A crash can lose at most the uncheckpointed frame markers; rescanning rewrites those bytes.
- **Close** keeps native receive progress in private app files. Reopen **Saved receives →
  Resume**, or rescan its identical header in Files, and reselect the **same bytes, safe
  filename and density/profile** at the sender. The deterministic ID lets it resume instead
  of mixing files. A different name/profile starts a different transfer. SHA-256 is checked
  again after resume, and active receives are file-locked against another receiver/discard.
- **Discard** explicitly removes only that private partial/checkpoint (including damaged
  progress), not files already saved publicly. Successful saves clean up progress. Keep
  enough free storage for the private snapshot/partial and a second copy during safe save.
  A killed sender may leave a disposable cache snapshot until cache cleanup.
- Browser bytes stay in memory. Closing/reloading the webpage loses unsaved browser receive
  progress; visibility changes only pause it. The browser does not claim disk-persistent resume.
- A generic QR scanner can display individual frame text but cannot assemble the file.
  Both ends need the Arc7 optical protocol implementation (Files or this companion).

**Privacy and trust:** codes are not encrypted or authenticated. Anyone who can see them can
copy them; SHA-256 detects corruption but does not establish who sent the file. Accept only
expected transfers. No received file is opened, installed or executed automatically.

### Protocol v2 (and backward compatibility)

Frames are ASCII, capped at **1472 characters** for v2. `crc` is eight lowercase hex digits
of CRC32 over everything preceding the last `|`; base64 is canonical padded standard base64.
`chunk` must be **128, 512 or 1024**. Empty files use one empty data frame. File size is
0–67,108,864 bytes and `totalFrames = max(1, ceil(fileSize / chunk))`.

```text
A7Q2|M|id|fileSize|chunk|totalFrames|sha256Hex|base64Utf8FileName|crc
A7Q2|D|id|zeroBasedFrameIndex|totalFrames|chunk|base64FileBytes|crc
```

New senders use the first 16 lowercase hex digits of SHA-256 of this UTF-8 string as `id`:
`sha256Hex + "\n" + safeFileName + "\n" + versionNumber + "\n" + chunkSize`.
All numeric strings are decimal, without leading zeroes. This is a resumable content/profile
identity, **not** authentication, encryption, a secret or a bearer URL. Receivers still
accept old random IDs. Metadata must match before progress can be reused.

Legacy **A7Q1** stays byte-compatible: at most 271 ASCII characters, 128-byte chunks,
0–1,048,576 bytes. Select **Legacy** to send to an older app/page. New receivers accept both:

```text
A7Q1|M|id|fileSize|128|totalFrames|sha256Hex|base64Utf8FileName|crc
A7Q1|D|id|zeroBasedFrameIndex|totalFrames|base64FileBytes|crc
```

Chunks are exact except the final short chunk. UTF-8 names are at most 96 bytes and must
be safe leaf filenames. Wrong numbers/profiles, path traversal, oversized metadata,
noncanonical base64, incorrect lengths, conflicting headers/duplicates and a bad full-file
SHA-256 all fail closed. A complete matching SHA-256 is required for explicit collision-safe
save, both for an uninterrupted receive and for a reopened checkpoint.

## Windows tools and removed Bluetooth Internet app

All six tracked batch files were replaced with ASCII/CRLF wrappers and a matching,
SHA-256-pinned PowerShell 5.1 helper. The complete `Windows-Tools.zip` is reproducible and
includes the helper, launchers, Python companions, offline QR webpage and instructions.
The Wi-Fi server is native/read-only/manifest-driven, with four bounded workers and range
support, no Python, obsolete branch fallback or administrator HttpListener URL ACL. USB
selection, safe in-place install, streaming ZIP/hash verification and visible error/exit
handling are shared. Stream Deck probes its interpreter before one application launch,
not a second interpreter retry after an app error. [Usage and limits](../tools/WINDOWS-TOOLS.md).

Bluetooth Internet's package/activity/manifest/catalog entry and bundle are retired. QR
Transfer takes its catalog slot; 32 default apps / 38 signed payloads remain. Existing
Bluetooth Internet installations are not silently uninstalled. Old pinned Bluetooth controls
open OS settings, not a dead Arc7 activity. System Bluetooth settings/USB Internet/Wi-Fi & VPN remain.

## Tests and remaining device checks

`make verify` includes `make test-runtime`, `make test-features`, `make test-optical` and
`make test-glass` equivalents, plus APK signatures/DEX/API17 manifests, exact payload
reconstruction, installer/routing/Weather/catalog and earlier suite regressions.

- Real Java and browser encoders feed their QR matrices through camera-like grayscale
  frames and the other reader; reassembled bytes must match. Tests cover rotated/mirrored
  sensors, noise, out-of-order/repeat/missing frames, CRC/SHA failures, paths/size limits,
  empty/partial/full chunks, snapshot mutation, duplicate names and cleanup; a complete
  >2 MiB transfer and 64 MiB boundary; legacy and all new densities; deterministic cross-language
  IDs; unclean process termination, checkpoint recovery/corruption/locking/discard.
- Actual compiled Music binding skips a lying HE-AAC initialization and prefers verified
  M4A before HLS. Tests preserve every AAC sample through ADTS conversion. Optional host
  FFmpeg explicitly maps audio and requires non-silent PCM in **ten** media outputs.
- Recording Android graphics fakes exercise the production glass's alpha/rims/press states,
  bounded wallpaper cache and screen alignment. Both app and launcher compile against API17.

These are host/synthetic-camera/graphics-fake tests, **not** real camera, GPU, Kobo decoder,
speaker/headphone/Bluetooth or live YouTube tests. On the tablet, verify optical transfers
in both directions with disposable files, missed frames and cancellation; check front-camera
focus/portrait/lighting, backdrop/text readability and battery/scroll performance; play the
previously failing Music track, then test Next/Stop during preparation and a denied stream.
