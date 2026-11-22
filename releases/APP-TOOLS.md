# App tools: private logs, playback tests, low-RAM mode, storage and resumable LAN uploads

Branch update for **Kobo Arc 7 / Arc 7HD · Android 4.0.3–4.2.2**. Package IDs, signing
keys stay unchanged; suite/defaults/upgrader now use **2.7.0 / 270**. This is not
an automatic public release, a physical-device certification or a modern-Android security update.

## 2.7.0 corrections: stream I/O and Stream Deck edit lock

- Finite DASH range reads and buffered HLS playlist/remux-resource reads get **at most
  three whole-request attempts** on an early EOF, the Android-4 `unexpected end of stream`
  error, socket timeout/reset, or gateway 502/504. Each attempt closes its connection and
  discards its incomplete bytes; it never appends unverified data from a failed response.
  Fresh connections also avoid Android-4 stale keep-alive reuse. Range, size and cache
  bounds remain enforced; 403/429/503 and malformed/misaligned responses are not retried.
  Cancellation interrupts the retry delay. Native TLS HTTP streaming now sends every byte
  after a short socket write (and retries EINTR) instead of silently dropping the tail.
- `MediaPlayer 1/-1004` remains an **I/O error**, not a codec/container diagnosis. Its UI
  explanation no longer incorrectly refers to a radio station when used by YouTube.
  These repairs cannot make an expired/denied/unavailable provider URL playable and do
  not retry a MediaPlayer callback, restart the queue or bypass provider restrictions.
  Native progressive/live-HLS bodies are still streamed, not buffered/replayed wholesale.
- Stream Deck opens the key editor **only while Edit Keys is enabled**. Normal-mode
  long presses do not open or consume an editor gesture. Hold-to-talk owns DOWN through
  UP/CANCEL and releases once on cancel, profile/grid redraw or app stop. Normal tap and
  toggle actions remain on release; entering edit mode never sends a macro/PTT press.

`make test-features` executes synthetic short/truncated HTTP response recovery, strict
range and retry-limit checks, production gesture-policy cases and deterministic short
native socket writes/EINTR. `make test-runtime`, `test-audio-state` and `test-reliability`
retain the media/focus/log/cache/QR/upload regressions. This is **not a live YouTube or
physical Kobo confirmation**: test the previously failing video and a held PTT key on
that device after updating the independent YouTube/Music/Stream Deck APKs.

## Install and find the tools

Stop current playback, then **update in place**. Do not uninstall or clear app data.
The full `KoboArc7Freedom.apk` bundles the matching signed companion/default APKs:
use **PATCHES → Apply / Retry All Patches**, accepting Android's install prompts if
root is unavailable. Same-version changes are detected by verified APK hashes.
Updating Music alone does not update the separate Radio, Podcasts, Home or Files apps.

- **YouTube Music → More → Playback diagnostics / sound test** or **App tools**.
- **YouTube → Sound** shows foreground-video diagnostics; More also offers background
  audio tests and App tools. The foreground and background players are distinct.
- In other owned apps, open the Android **Menu → App tools · logs & storage**.
- A universal visible route is **App Manager → Manage [the installed app] → App tools**.
  This launches that app's own screen; App Manager never reads another UID's private files.
- **Home → long-press App Bar → Customize → Home app tools**; Aurora also has an App
  tools button on its account/settings page. The upgrader has the Android Menu entry.
- F-Droid keeps its original `FDroidApp` and database/repository migration. A private
  bootstrap provider initializes its tools; use App Manager to open them.

Older installed versions have no tools screen: update that individual APK first.

## 1. Playback diagnostics and genuinely offline sound tests

The audio diagnostics screen displays prepared/playing state, audio focus, ducking,
buffering percentage (when reported), guarded position/duration, known container,
verified AAC-LC configuration where available, compatibility-retry state, and the exact
last **MediaPlayer `what/extra` numbers**. Unknown codec/container information is marked
unknown, not guessed from a title or URL. YouTube's Sound dialog separately shows its
foreground readiness, buffering, focus and native error numbers.

Choose **Record current playback counters in debug log** to attach a sanitized snapshot
to the app's normal log; then view/copy/save it from App tools. No source URL, token,
track title, local path, filename or provider response is attached. A status of Playing
is **not** proof that audio reached the speaker or headphones.

Three bundled, checksum-pinned clips contain an original, quiet, approximately
**1.5-second 440 Hz tone**: PCM/WAV, MP3 and AAC-LC/classic M4A. They use local assets,
not a provider, internet connection or microphone. Start at a low **media** volume.
Starting a test requires confirmation, stops current playback, cancels this app's sleep
timer, and bypasses app EQ. It never raises system volume or forces speaker routing.
Playback does not automatically return to the previous song afterward.

Use **I heard the last clip / I did not hear it** to record your own audible-output
observation, separately from player state. If none is audible, check media volume,
headphones/Bluetooth routing and another known local file. If WAV/MP3 work but AAC fails,
the decoder/container path needs investigation. If all local clips work but an online
track fails, that narrows the investigation; it does not establish that the provider is
working or identify a specific network/codec cause on its own.

The earlier `-38/0` readiness/release fix and one-shot genuine AAC container recovery
remain. Diagnostic polling does not query unprepared native players.

## 2. Shared-player focus, headphones and media buttons

Music, Radio, Podcasts and the other shared-audio owners use the same policy:

- Request focus **only when a prepared player is about to start**, not while an idle
  watcher, metadata request or preparing player runs. Denied focus leaves playback
  paused with a manual-retry explanation.
- Duck to 20% app volume on duckable loss, respecting the sleep fade; restore on gain.
- Pause on transient loss. Resume only the same live player/request on gain, and only
  if the user has not paused/stopped, unplugged headphones or chosen another item.
- Permanent focus loss and headphone disconnect pause. Reconnect never auto-resumes.
  An unplug during preparation also vetoes the eventual automatic start.
- The focused/prepared owner registers Android's legacy media-button receiver. Handle
  one non-repeating key-down: headset/play-pause, explicit Play/Pause, Next/Previous,
  rewind/fast-forward and Stop. Explicit Play/Pause are idempotent, not toggles.
- Idle/cold APKs cannot steal those button events. Media buttons do not resurrect an
  app after its playback process has been destroyed.

YouTube foreground video also pauses on noisy-output disconnect and guards focus
callbacks by their original player/request; foreground-video transient-loss recovery
remains manual. Official VLC keeps its native playback/focus behavior.

## 3. Optional per-app low-RAM profile — glass stays transparent

Turn on **Low-RAM profile** in each app's own tools. It is opt-in and independent across
UIDs; reopening the main screen refreshes existing UI artwork. No forced app restart,
root command, app-data cleanup, opaque gradient replacement or animation/blur loop is used.

| Work | Normal | Low-RAM |
| --- | ---: | ---: |
| New/cached media artwork longest edge | 256 px | 128 px |
| YouTube thumbnail cache | 32 entries | 8 entries |
| Bounded artwork workers / waiting queue | 3 / 24 | 1 / 24 |
| Locally registered media preparations | up to 3 | 1 (new overlaps refused) |
| Home's cached wallpaper frost longest edge | 160 px | 96 px |
| Default managed temporary-media budget, unless explicitly set | 256 MiB | 64 MiB |

Now-playing image requests are coalesced in memory; queued art can be discarded under
memory pressure and retried later. Already-running work is allowed to unwind, not
killed. This limits these specific expensive artwork/remux paths, not every thread,
third-party engine, camera operation or Android process. It does not promise a particular
free-RAM figure or eliminate out-of-memory failures on obsolete hardware.

## 4. Storage: count saved media, clear only temporary media

App tools shows this UID's **managed temporary-media size**, selected limit and active
state; other cache is counted read-only. Known public saved-media locations for that
app (Music/YouTubeMusic, Movies/YouTube, Podcasts, Music/Recordings, Music, or Aurora's
Download/AuroraStore) are separately counted read-only. These folders can be shared with
other apps; accounting is bounded and marked partial if unavailable/too large. Vaults,
arbitrary SD-card trees and private document data are not scanned as media downloads.

The selectable temporary-media limit is **32 / 64 / 128 / 256 / 512 / 1024 MiB**. It covers
the remuxer's spool **and** output, so preparation can need roughly twice the final media
size. A lower limit never cancels existing managed playback/download preparation; it
fully takes effect when their leases end. New work can be refused until space is free.
Oldest unused managed files are trimmed when a new preparation reserves space. Unknown
output sizes are also budget-checked as they are written.

**Clear temporary media cache** requires confirmation and is blocked while a managed
player/preparation/download is active (paused prepared players still count). Only
`cache/media-temporary` is eligible. Symlink traversal is refused. Stop active playback
first. No saved download, music library, recording, vault, optical receive journal,
LAN upload partial, other cache or app preference is deleted. Existing legacy cache
outside this dedicated directory is not silently swept. The separate App Manager
`pm clear` action remains destructive and is **not** this cache-only control.

## 5. Large-file trusted-LAN uploads: resume and final SHA-256

In **Files**, open the folder you want to share → **QR link · LAN share**. The website
still lists/downloads files and offers uploads; existing range downloads remain.

1. Scan/open the temporary link on the other device. Select files, then **Upload**.
2. The browser hashes each file incrementally, reading at most **1 MiB at a time**;
   no multi-gigabyte whole-file buffer, remote script or HTTPS-only WebCrypto is needed.
3. Each **1 MiB or smaller** chunk has its own SHA-256. The Kobo syncs data and a private
   journal before acknowledging its offset. A partial/corrupt chunk rolls back to the
   previous acknowledged offset; acknowledged chunks survive a connection/process restart.
4. If interrupted or paused, click Upload with the **same file selected**. It is
   rehashed and matched by exact destination/name/size/checksum, then resumes. No file
   bytes, bearer token or persistent localStorage resume secret are saved by the browser.
5. The Kobo streams a final whole-file SHA-256 check before reserving a collision-safe
   destination and publishing the completed file. A mismatch publishes nothing. Retrying
   after a lost completion response uses its receipt, not a duplicate file. Completion
   receipts are rehashed before init/status/finish can report success, even if a saved file
   still has the same size and timestamp. A changed saved copy is left untouched; reselecting
   the original source starts a new collision-safe upload. Explicitly uploading unchanged
   identical name/bytes again reuses its valid receipt; rename to request another copy.

The **per-file limit is 4 GiB**, subject to free space, filesystem limits and real-world
transfer speed. There are at most **four pending uploads** across this app and 32 small
completion receipts. Upload chunks/final checksum work are serialized; another operation
can report Busy and be retried rather than queue large bodies in memory. Checksum protects
integrity against accidental corruption, **not authenticity, eavesdropping or a malicious
LAN peer**. HTTP is unencrypted; use a trusted network and do not transfer sensitive data.

The bearer still expires after **30 minutes**. Stop/restart the share for the **same exact
Kobo root folder**, scan its new link, and reselect the same file to recover. Old links
stop authorizing requests. Recovery is allowed for **seven days since the last checkpoint**;
expired/unwanted bytes remain until explicitly discarded, not deleted as media cache.
Stop sharing, open the original shared folder → QR link · LAN share → **Pending uploads**
to see received size and confirm partial-only discard. Published files and optical QR
receive data are never deleted by that operation. Commit-recovery metadata is handled
conservatively: cleanup never deletes a destination file/reservation, even if empty.

Temporary names/journals are hidden from the website and cannot be downloaded by known
names or symlinks. Token→HttpOnly-cookie exchange, POST bearer header/same-origin checks,
canonical folder bounds, bounded request queues and collision-safe names are retained.
Old one-shot clients still work for files ≤8 MiB; they receive the server SHA-256 and can
supply an expected checksum. They do not gain resume, and should use the chunk API for
large files. SHA-256 is required by the new browser uploader.

**Offline optical QR is separate and preserved.** The independent QR Transfer app and
`tools/qr-transfer.html` still transmit actual file bytes through camera-scanned frames,
with a 64 MiB optical limit, CRC/SHA checks and their own receive checkpoints. A LAN link
QR is not a replacement for optical transfer. See [the optical guide](GLASS-MUSIC-QR.md).

## Per-app debug logs and privacy

Every Arc7-owned APK, Home, Aurora, upgrader and patched F-Droid has an independently
initialized, owner-private **structured** log: startup/version/SDK, activity component,
memory pressure and relevant operation/error counters. Playback adds focus/media-button,
preparation/retry/error and explicit diagnostic snapshots; LAN adds checkpoint/verification
counters; Aurora/upgrader/F-Droid add operation/failure events. Generic lifecycle/crash
logging is not a trace of every caught error or native/system log in every app.

- **Three files × 128 KiB**, rotated locally (384 KiB maximum); a view/export snapshot is
  bounded to **64 KiB**. Ordinary logging never starts a server or uploads anything.
- Default is on for local debugging; disable it per app, refresh, write a test event,
  or **Clear private debug logs**. Disable does not silently erase old logs.
- Fields are constant event codes, validated component/class/method identifiers, numeric
  values, and at most 12 Java frame identifiers. No exception message, cause, source
  filename, thread name, URL/token, command, query, password, TOTP seed/code or content is
  recorded by these call sites. Identifier validation is **not** a magic secret detector:
  developers must never pass dynamic user input as an event code.
- Copy and Save both require confirmation. On Android 4, other apps can read the clipboard.
  Save writes a public text copy under **Download/Arc7 Debug** where permissions allow;
  apps without storage permission can still view/copy, without being granted new network,
  microphone or storage permissions. Review any copy before sharing.
- Clear affects only private rotations. It does not delete already-exported copies.
- F-Droid's earlier raw `files/arc7-last-crash.txt` collector is retired and that obsolete
  private log is removed. Its guarded post-sync stability fixes remain; new crash frames
  use the bounded logger and delegate to Android's previous crash handler.

### Official VLC exception — deliberately not re-signed/injected

The exact upstream VideoLAN APK is retained (SHA-256
`4dc46242f792ebb598b9a957abfaaf13b4540870981e56e3f845706b378dd789`). Its own
**Settings → Advanced → Debug logs** screen records, stops, saves/shares and clears logs.
App Manager's tools action explains and opens VLC rather than trying to read its private
files. Native VLC logging is **not Arc7's bounded/sanitized collector** and can contain
media paths/URLs: start it only for reproduction, stop promptly, inspect before sharing,
and clear it afterward. Arc7's low-RAM/cache settings do not rewrite VLC's native engine.
The source-built emergency VLC fallback does get Arc7 App tools; it is not substituted
for an available official binary or silently installed over another signing identity.

## What has been tested — and what still needs a Kobo

`make test-reliability` runs production pure Java against the host filesystem/HTTP stack:
log rotation/concurrency/privacy/permissions, cache leases/limits and saved-data isolation,
profile worker admission, durable chunk/checksum/rollback, real socket truncation, share
restart/new token, canonical bounds, before/after-rename crash replay, duplicate finish,
quota/expiry, same-size/same-timestamp saved-copy mutation and partial-only discard. It
also checks combined spool/output cache accounting during real remux output and an asset-less
legacy server's actually served small-file browser client (no broken script links). It executes
the production browser code with fakes, NIST SHA-256 vectors, streamed 513 MiB bit-length coverage, 4 GiB length encoding, and
bounded-memory/lost-response resume scenarios. Host FFmpeg checks the three local clips
are short, quiet and non-silent. These are synthetic clips, not provider content.

`make test-audio-state` exercises the production service/receiver using recording Android
fakes, including focus denial/ducking/transient/permanent changes, stale callbacks,
headphone disconnect during prepare, button ownership and explicit Play/Pause semantics.
`make verify` additionally checks the shipped APK manifest/DEX/bootstrap/offline-asset
coverage, permanent signatures/API/ABI constraints, and all prior suite/Windows/QR/media
regressions. Home, Aurora, upgrader and the emergency VLC fallback also receive an API17
resource/Java compile check using the release toolchain. This environment has no Gradle
installation/wrapper, so those direct checks are not a claim of running Gradle module builds.
None of those tests substitutes for physical speaker/headphone/GPU/camera, actual
flash/power-loss durability, large-file filesystem/LAN, or live-provider testing.

On the Kobo, check both outputs at low volume, unplug during a load and while playing,
interrupt one app with another audio app, test headset transport, stop/resume a LAN upload,
then restart its share and compare SHA-256 of the saved file. Preserve a backup and update
in place; do not clear app data just to obtain a cleaner test.
