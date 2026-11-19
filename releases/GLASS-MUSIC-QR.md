# Liquid-glass-inspired UI, Music format recovery & offline optical file transfer

Branch build for Kobo Arc 7 / Arc 7HD, Android 4.0.3–4.2.2 (API15 minimum / API17 target).
Versions and permanent signing identity are retained. This work does **not** publish a
new public release or change your installed app data automatically.

## Install in place

- Install this build's `VanillaAndroid4Home.apk` for the new Home dock/top capsule.
- Install `YouTubeMusic.apk` for the Music playback repair. Stop any existing playback
  before updating. Do **not** uninstall or clear data.
- Install the generated `apps/Files.apk` for optical sending/receiving and its new camera
  permission. The full `KoboArc7Freedom.apk` / 2.6.0 upgrader also bundles the same signed
  Home, Music and Files payloads; use **PATCHES → Apply / Retry All Patches** and confirm
  any pending Android install prompts when not rooted.
- Default app APKs are generated/ignored by Git, available in the build artifact and
  local installer portal. The committed suite/upgrader embed their exact signed bytes.
- Check `SHA256SUMS.txt` for these exact build files. An older public “latest” asset may
  not include this work, even though it has the same version number.

## Glass: what changed, and what Android 4 can actually render

The default glass material is now **Apple-Liquid-Glass-inspired**, rather than an almost
opaque gradient: a translucent tinted body, a soft lift, broad lens-like sheen, directional
bright/dim specular rims, a fine inner edge and a pressed highlight. Buttons and the floating
Home dock use larger capsule curves. Shared cards, elevated surfaces, buttons and headers
use the material, and Music's search/player/list surfaces now use it too.

Home's glass dock and top capsule show a **small, cached, blurred wallpaper sample**, aligned
to their screen position. The sample is at most 160 pixels on its longest edge; it is not
recaptured during drawing or animated each frame. Wallpaper/skin/resume changes refresh
it. The clear-opacity override removes the body and frost while retaining the lens rim.
Solid, neon, full-width and minimal Home alternatives remain available. Music keeps a dark player palette with white
transport typography even when the OS daylight skin is selected.

This is not Apple's proprietary renderer and **not live refraction/backdrop blur of moving
app content**. Android 4 has no modern RenderEffect pipeline. The implementation uses
API15-compatible Canvas, gradients, alpha and a bounded wallpaper-only blur, without
screen capture, an always-running effect loop, API21 elevation or hardware clipPath calls.
App pages use static ambient colour to make the transmitted background visible.

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

1. In **Files**, long-press a file and choose **Send file bytes via QR (offline)**.
   For a folder, make a ZIP first. The limit is **1 MiB**; small documents work best.
2. On the other Kobo, open **Files → Receive QR bytes**. On a computer/phone, save and
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
- At normal speed, 10 KiB needs roughly a minute even with perfect scanning. More missed
  frames require more loops. Large media/APKs are much better sent by the separate LAN share.
- Sender bytes are a private snapshot, so editing the original during transfer does not
  mix file versions. Receiver partials stay private until verification and explicit save.
  Closing the activity/page cancels; interrupted transfers are not resumable after process
  death. Cache partials may remain until Android clears the cache if the process is killed.
- A generic QR scanner can display individual frame text but cannot assemble the file.
  Both ends need the Arc7 optical protocol implementation (Files or this companion).

**Privacy and trust:** codes are not encrypted or authenticated. Anyone who can see them can
copy them; SHA-256 detects corruption but does not establish who sent the file. Accept only
expected transfers. No received file is opened, installed or executed automatically.

### Protocol v1 (interoperability)

Each QR contains ASCII text, at most 271 characters. `crc` is lowercase eight-hex-digit
CRC32 over everything preceding the last `|`. Base64 is canonical, padded standard base64.
The transfer ID is 16 lowercase random hex digits, not a bearer URL.

```text
A7Q1|M|id|fileSize|128|totalFrames|sha256Hex|base64Utf8FileName|crc
A7Q1|D|id|zeroBasedFrameIndex|totalFrames|base64FileBytes|crc
```

Chunks are 128 bytes except the last. Empty files use one empty data frame. File size is
0–1,048,576 bytes, frame count is derived/bounded, and UTF-8 names are limited to 96 bytes
and must be safe leaf filenames. Path traversal, oversized metadata, malformed base64,
wrong payload lengths and changed headers are rejected before saving.

## Tests and remaining device checks

`make verify` includes `make test-runtime`, `make test-features`, `make test-optical` and
`make test-glass` equivalents, plus APK signatures/DEX/API17 manifests, exact payload
reconstruction, installer/routing/Weather/catalog and earlier suite regressions.

- Real Java and browser encoders feed their QR matrices through camera-like grayscale
  frames and the other reader; reassembled bytes must match. Tests cover rotated/mirrored
  sensors, noise, out-of-order/repeat/missing frames, CRC/SHA failures, paths/size limits,
  empty/partial/full chunks, snapshot mutation, duplicate names and cleanup.
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
