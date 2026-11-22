# Arc7 Freedom 2.6.9 — app and runtime repairs

Native Android **4.0.3–4.2.2**, minSdk15 / targetSdk17, ARMv7, DEX035.
Suite, default apps, YouTube/Music and upgrader: **2.6.9 / 269**.
Home: **5.6.0 / 56**. Patched F-Droid: **1306**. Permanent signing identity retained.
The matching **v2.6.9** GitHub release is published only after the main-branch workflow succeeds.

## Install without clearing data

1. Back up important data and stop playback. Install [KoboArc7Freedom.apk](KoboArc7Freedom.apk)
   **in place**, then open it once. Alternatively install the
   [2.6.9 upgrader](Upgrade-My-Kobo-To-v2.6.9.apk), which embeds exactly the same suite.
2. Open **Arc7 Freedom → PATCHES → Apply / Retry All Patches** and inspect the report.
   Patch management is a tab, not a screen automatically opened by PC scripts or the
   upgrader. The old App Patches activity only redirects old manual shortcuts to that tab.
3. The full suite bundles **38 signed APKs**: Home, F-Droid, Aurora, VLC, YouTube,
   YouTube Music and **32 default apps**. Each installed app has its own UID, permissions,
   private data, task and (where needed) network/TLS engine.
4. With root, normal in-place updates run and verify installed bytes. Without root,
   explicitly tap **Install pending apps** and confirm each APK in Android. Cancelling
   or leaving the app/tab pauses the queue. Root-only work remains pending.
5. A newer installed app is retained. A signature/storage/update failure is **not** a
   reason to uninstall, clear data, remove a system APK or claim success. Follow the report.

Standalone updates are in `releases/apps/` plus the companion APKs in `releases/`.
They update **only that app**. Default APKs/chunks are generated outputs, intentionally
ignored by Git; the suite/upgrader embed their exact signed bytes. The build artifact and
local Wi-Fi portal also include them. [SHA256SUMS.txt](SHA256SUMS.txt) covers every APK.
An older public “latest” download may not include this branch work.

Maintenance does not automatically wipe/flash/convert the OS, remove Kobo/stock apps,
enable ad blocking, change storage policy or require a proxy/VPN/tether. Those remain
explicit separate tools. Only obsolete Arc7 `localhost:8118` / `127.0.0.1:8118` proxy
settings are retried for cleanup; unrelated proxies/credentials/routes remain intact.
See [maintenance details](INSTALL-RUNTIME-FIXES.md).

## F-Droid: legacy catalogs are not installed-app lists

Install the included **1306** build without clearing F-Droid data, then refresh repositories.

- Available apps default/fall back to **All**, not the time-limited What's New view.
  A one-time migration removes the old category selection. Empty-category and update
  wording no longer implies every repository app is installed/up to date.
- **F-Droid Archive** (`https://f-droid.org/archive`) and **Guardian Project Archive**
  (`https://guardianproject.info/fdroid/archive`) are enabled once, using the certificates
  already pinned in the bundled repository configuration. There are seven built-in rows.
  Archives offer older APKs, but not every app supports Android 4.x or remains safe to use.
- Existing certificate pins, credentials, repository data and custom repositories are
  retained. Missing archive rows are inserted, not replacements for user trust records.
  Failed migrations are retryable. Subsequent user repository choices are respected.
- Catalog metadata is still signature-verified. The installed-app join is not populated
  from catalog entries. The older array/mirror/request, low-memory and crash repairs remain.
- Large legacy archives can take time/data to synchronize. Repository/server availability
  and installation on a real ROM still need device validation; a successful host migration
  is not a claim that every live catalog can be downloaded today.

## Independent apps and styling

| App | APK / package |
| --- | --- |
| Radio (existing identity retained) | `Radio.apk` / `ca.arc7freedom.radiorss` |
| Podcasts | `Podcasts.apk` / `ca.arc7freedom.podcasts` |
| News | `News.apk` / `ca.arc7freedom.news` |
| Authenticator | `Authenticator.apk` / `ca.arc7freedom.authenticator` |
| Passwords | `Passwords.apk` / `ca.arc7freedom.passwords` |

Podcasts has its own search/feed/episode playback/download UI; News has its own offline
RSS reader/custom feeds. They are not links to old Radio tabs. Shared Java source does not
share a process or data. Radio data stays in place; its optional **Copy existing custom
feeds to News** action copies only URLs, after confirmation in News, without deleting data.

Shared cards, buttons and headers now use **Apple-Liquid-Glass-inspired** translucent lenses,
directional specular rims, fine inner edges and capsule curves. Home's default dock and top
capsule add a small, cached, screen-aligned wallpaper frost; app pages use static ambient colour.
This is an API15-compatible Canvas material, **not Apple's renderer or live background
refraction/blur**. Solid/minimal Home alternatives and opacity controls remain.
See the [glass, Music and optical QR guide](GLASS-MUSIC-QR.md). The in-app Quick App
Bar Switcher is removed. Home's pinned dock, app slots, search button and other layout/
header controls remain; old switcher settings cannot turn it back on.

## Quick access: podcast favorites and uncrowded search

### Favorite podcasts

1. In **Podcasts**, search the directory, select a show and tap **Favorite**. You can also
   favorite a show from its episode list without starting playback or downloading an episode.
2. The **Favorite podcasts** card is at the top. **Quick search favorites** filters as you type
   by show title and publisher, case/accent-insensitively, with all query words required.
   **Clear** restores the full list. No network request is made by this local filter.
3. Tap a saved show to load its episodes, then stream/download as before. Tap its **★** and
   confirm to remove just that favorite. Up to **200** shows are retained across restarts;
   duplicate feed identities are not added again and existing Radio/News data is untouched.

The favorite names/feed URLs can be searched offline; opening the actual episodes still needs
an available feed connection. This is local storage, not podcast-provider account sync.
Invalid feed entries, failed saves and corrupt saved lists are reported rather than claimed
as success or silently replaced. Late directory/feed dialogs are ignored after another request,
Pause or destruction.

### YouTube and Music headers

Both apps now use a compact logo/title/**More** row and a separate full-width search row.
There is no row of EQ/Sleep/Morphe/app-switch buttons squeezing the text field. Tap **Go**
or the keyboard Search/Enter action to submit. Music's short **Songs ▾** selector changes
search type (including **Artists**); the full type names remain in its chooser.

- **YouTube → More:** open YouTube Music, or Morphe / playback settings.
- **Music → More:** Equalizer, Sleep timer, Morphe / playback settings, or open YouTube.

The title can ellipsize instead of stealing input width. Go/More/type controls have at least
48dp targets; the input has its own weighted space, including at 320dp widths. Host tests
record allocations and events, not real Android font rendering or keyboard/window behavior.

## CBC and media playback

The reported **MediaPlayer 1/1004** is kept literally, distinct from negative `1/-1004`.
Both represent an actionable stream-I/O error in the UI. **1/-1010** reports unsupported
format instead of pretending playback worked.

- Regional CBC MP3 mounts use the canonical `_CBC.mp3` addresses. Toronto Radio One has
  a bounded alternate HLS path through Radio's own TLS/relay. Waterloo is **not** silently
  replaced with Toronto programming. The fallback belongs only to its originating request.
- Conservative stream selection requires H.264 at most 720p/30fps and AAC-LC, at most
  stereo/48kHz, with a preference for 128kbps audio. Progressive video must advertise real
  audio. Unsupported codecs, live fMP4, malformed or incomplete media stay explicit errors.
- Playback status/notifications belong to the **actual prepared player** in YouTube/Music/
  Radio/etc. VLC owns its notification; external observers update Clock/widgets silently.
  Prepare/start failures are not Playing. In-app YouTube video volume is explicitly enabled.
- Offline MP4/M4A downloads resolve a real compatible stream, merge or remux when required,
  validate output, sync and atomically rename a unique temporary file. No overwriting an
  existing recording and no saving a raw unsupported stream with a misleading extension.
- Downloads have visible progress and Cancel; only one job can run per media activity.
  Cancel/destroy interrupts the worker and removes unfinished output. Already completed
  files are retained. Preparation with unknown length uses indeterminate progress.
- YouTube MP4 files go to `/sdcard/Movies/YouTube/`; M4A files go to
  `/sdcard/Music/YouTubeMusic/`. Live/TS HLS is playback-only, not an offline MP4 promise.
  Finite fMP4/DASH preparation may need substantial free cache/storage and time.

### YouTube video without sound

The previous HLS relay could generate a master with separate MPEG-TS video/audio playlists.
Android 4.2's HLS player ignores that alternate-audio playlist, so a picture could start
without sound even with `MediaPlayer` volume enabled. Playback now prefers an embedded-audio
rendition, inspects actual TS PAT/PMT audio/video tables (including checksums), and tries up to
three compatible HLS renditions before falling back to a combined H.264/AAC DASH remux or
another client. Separate **fMP4** renditions are still remuxed; separate **TS** renditions
are not falsely claimed to work. An unavailable compatible pair remains an explicit error.

The prepared-player audio guard runs before start/Playing in video and background playback.
If the video player reports no audio, one bounded DASH recovery is attempted with cancellation
and stale-request guards. ROMs without track-info APIs are marked unverified, not falsely
classified as audio-less. This is not a guarantee of audible output on untested hardware.

Install this build's [YouTube.apk](YouTube.apk) in place, then reopen YouTube. The version
is now 2.6.9/269; an older public “latest” file may not contain the repair. **Sound** opens
media-volume controls and the current audio-check result. Hardware volume buttons target
media, audio-focus loss pauses/ducks video, and zero media volume produces a warning. The
app never forces speaker/Bluetooth routing or raises the system volume automatically.

Host regressions reproduce the separate-TS trap, a lying CODECS declaration, missing/corrupt
program tables, combined-rendition and actual DASH fallback, and prepared-player audio checks.
With FFmpeg enabled they explicitly select the audio stream and require decoded non-silent
PCM in all ten generated MP4/M4A/ADTS outputs; decoding only the video is not a passing audio test.
Actual Kobo speaker/headphone/Bluetooth output and live provider behavior still need testing.

### YouTube and Shorts

**Pinned Channels** is explicitly local. Add a channel ID, public channel URL or handle;
view its public recent-video feed, or long-press to unpin. Pins survive restarts but are
not Google-account subscriptions, subscriber counts or push updates.

Provider bot/sign-in/age denials are terminal, visible access-required errors. Shorts
use the public video resolver and the same codec policy. **No authentication/bot bypass
is supplied or promised**; some Shorts/videos will remain unavailable on this device.

### YouTube Music

- **Unsupported-format (`1/-1010`) repair:** prefer verified AAC-LC adaptive audio before HLS,
  parse real `mp4a/esds` AudioSpecificConfig bytes, reject HE-AAC/SBR/encryption/unsupported
  channel-rate modes and normalize MP4 sample-entry channel/rate fields. Music prepares an
  audio-only classic M4A rather than unnecessarily opening a video/HLS container.
- One request-scoped **ADTS AAC-LC** retry handles supported container/decoder errors. It
  preserves the AAC samples, cancels late/stopped requests and never loops or hides failure
  by advancing tracks. This is not transcoding; unavailable compatible audio remains an error.

- **Quick play** uses local Liked Songs, otherwise loaded songs, otherwise one public-song
  search. It is bound to that request, not a late response from a different search.
- The search picker offers **Songs, Artists, Albums, Playlists and Community playlists**.
  **Artists** requests actual artist results and retains their public browse identities, not
  a pretend song ID. Tap/Open an artist to see its public songs, albums and singles/EPs
  (up to **60** first-page items); tap an album to open its own tracks. **Back to artist
  results** restores the prior result list. An 11-letter artist name is still a name, not
  automatically interpreted as a video ID in this mode.
- Artist/album/playlist searches return honest empty/error states, not unrelated song search
  fallbacks. Artist headers' radio/shuffle controls, unrelated sidebars and related-artist
  carousels are not substituted for that artist's songs/releases. Unsupported/sign-in-only
  layouts remain unavailable. Public remote collections load up to **60 first-page tracks**.
- Save albums/playlists to the local library. Long-press a song to add it to a local
  playlist; create one with **+ New playlist**. Use **Play results** to start loaded songs.
  Long-press a saved collection to remove it. Local playlists preserve addition order.
- Collection browse/watch aliases deduplicate correctly. Shelf/quick-play callbacks are
  request-scoped; service binding and downloads retain their own ownership. Lyrics are
  keyed to song identity/title/artist, not title alone, and ignore stale/destroyed callbacks.
- These are local/public features, **not Google-account login, cloud library sync or an
  unlimited/paginated streaming subscription**. Lyrics and playback depend on providers.

## Weather high/low and widget repair

Weather and its widget explicitly show **High / Low** with units, location and freshness;
missing data remains **—**. The independent Weather APK owns city selection, jobs and cache.
A signature-protected read-only snapshot/refresh bridge lets legacy suite widgets display
that state without having the suite fetch weather or exposing coordinates/credentials.

Update both Weather and the suite, open Weather, select a city and refresh. Prefer the
new **Weather-supplied** widget. Remove/re-add a widget that stays stale after installation;
actual signature/install-order enforcement, launcher sizing, reboot/registration and visual
clipping still need a Kobo smoke test. [Expanded Weather guide](WEATHER-FEATURES.md).

## Stream Deck key assignment

1. Run the updated `tools/Start-PC-Stream-Deck.py` companion on your PC. Use only a trusted
   LAN/USB/Tailscale link; it is a PC-input remote, not a hardened public Internet service.
2. In Stream Deck, open **Edit Keys**, choose a button and its **HOTKEY** action.
   Use **Assign key / keyboard shortcut…** for Ctrl/Alt/Shift/Win and a supported key; type a canonical
   combo such as `CTRL+SHIFT+F13` if preferred. Invalid bindings are rejected, not mapped to F13.
3. Choose tap/toggle/momentary mode as supported by the backend. Ordered outgoing packets
   and per-client held-key reference counts keep overlapping shortcuts from releasing
   another button's modifier. Leaving the foreground/disconnecting releases owned holds.
4. Windows uses explicit key flags; Linux requires **X11 + xdotool**. Native Wayland input
   is unsupported. macOS uses mapped key codes for taps; held/momentary mode and unmapped
   keys report unsupported instead of a false success. Real OS input is not host-tested here.

## Authenticator and Passwords: local, limited, unaudited

**Android 4.x no longer receives security support. These apps have not had an independent
security audit. Root, malware, an untrusted keyboard or clipboard reader can expose unlocked
secrets. Do not treat this old tablet as equivalent to a supported, audited password manager
or hardware security key. Keep provider recovery codes and encrypted backups elsewhere.**

- Each app has its own private, purpose-bound encrypted vault and no `INTERNET` permission.
  Android automatic app-data backup is disabled. There is no cloud sync, recovery service,
  password autofill, 2FA QR scanning, HOTP or push authentication.
- Create a strong unique master passphrase (minimum 10 characters). Password derivation
  uses PBKDF2-HMAC-SHA256 at 600,000 iterations with random salt; independent AES-256-CBC
  and HMAC-SHA256 keys authenticate the header/IV/ciphertext **before decrypting**.
  Writes are bounded, synced and atomic. Slow derivation on the Kobo remains to be measured.
- Apps lock on leaving/background or after **2 minutes idle**. Sensitive state is not put
  in Android saved-state bundles, plaintext preferences, logs or screenshots. Java/IME memory
  cannot be guaranteed erasable. Copied data clears after 30 seconds if still owned; other
  apps can read Android 4.x's clipboard in that interval. Auto-clear is best-effort if killed.
- Authenticator accepts a Base32 secret or `otpauth://totp` URI, SHA1/SHA256/SHA512,
  6/8 digits and a 15–120 second period. Set the device date/time accurately. It does not
  contact an account provider or generate/store the provider's recovery codes.
- Passwords stores title, username, password, website and notes, and can generate a random
  24-character password. Websites are not automatically opened.
- **Export encrypted backup** writes to `/sdcard/Download/Arc7Backups/`. Copy the file
  elsewhere. Restore asks for its password and explicitly replaces that app's current
  vault only after validation. Changing the password does not re-encrypt older exports.
  There is no forgotten-password recovery. Android uninstall deletes the private vault.
- Limits: 200 entries, approximately 1MiB plaintext vault data. Cancelled/failed operations
  retain previously committed data; a save already committed before cancellation is kept.
- Both apps import unencrypted Proton Pass and Bitwarden CSV/JSON exports locally (up to 4 MiB
  per file / 200 entries). Passwords retains supported login TOTP secrets; Authenticator imports
  valid TOTP secrets/`otpauth://` values. Imports append and skip duplicates; they do not replace
  existing entries. Provider-encrypted exports, passkeys, push approvals and recovery codes are
  not imported. The selected export is parsed on-device and is not uploaded.
- Files offers a consent-based, 30-minute QR link to a folder inside shared internal storage.
  A browser on the same trusted LAN can upload/download files; the random bearer link is folder-
  scoped but local HTTP is unencrypted. Stop sharing in Files when finished; this is not suitable
  for sensitive transfers or an untrusted network.

## Files: offline camera QR bytes, separate from LAN links

Long-press a file → **Send file bytes via QR (offline)**. On the other Kobo, use **Receive QR
bytes**, or use the self-contained [browser companion](../tools/qr-transfer.html) on another
computer/phone. The independent **QR-Transfer.apk** adds its own offline send/receive hub,
built-in Kobo file browser, optional system picker/Share, density profiles and **Saved receives**.
Actual bytes travel in repeated frames, not a URL. Maximum **64 MiB** in v2; **Legacy v1**
remains 1 MiB. Standard 512-byte density takes about 27 minutes per MiB for a perfect clean
pass at normal speed (64 MiB can exceed a day); missed frames add time. ZIP folders first.

Accept the header, watch missing frames and replay as needed. CRC32 and full-file SHA-256
checks precede explicit **Save verified file**. Public files are collision-safe, never opened
or installed automatically. Native send/receive buffers are bounded and bytes stay on disk;
receive checkpoints sync every 32 new frames, on completion and on close. Resume from the
same file/name/profile, or explicitly Discard private progress. Browser receive data remains
in memory only while the page is open. Codes are **not encrypted or authenticated**.
Browser camera reception needs a secure context (HTTPS/localhost; local files work in some
browsers); a saved page can display codes for the Kobo camera offline.

The older **QR link · LAN share** remains a separate, temporary, folder-scoped HTTP share.
See [setup, protocol, limits and tests](GLASS-MUSIC-QR.md).

## Validation

**This build passed:** the complete signed build and full `scripts/verify-apks.py`
run, including the compiled MorpheEngine integration (not skipped), host FFmpeg,
new feature/catalog/Weather regressions and byte-exact reconstruction of all **38**
embedded APKs. All **53** release/app/tool checksums were independently checked.
This is host/binary evidence, not tablet playback or a security audit.

Exact suite, upgrader, YouTube/Music, Podcasts and other artifact hashes are generated in
[SHA256SUMS.txt](SHA256SUMS.txt). Use that authoritative manifest for the current signed build;
CI rebuilds can change APK bytes without changing their version numbers. In this release,
suite/upgrader and all 34 standalone app binaries must explicitly be **2.6.9 / 269**;
Home, stores and third-party VLC retain their independent versions.

Automated host and binary checks are recorded in the ignored build logs and run by `make verify`:

| Gate | What it establishes |
| --- | --- |
| `make test-release` + `scripts/check-release-version.py --artifacts` | Detect a workflow-only version bump, stale manifest/Gradle/tool paths, missing/corrupt payloads and obsolete upgrader artifacts before staging; all 53 SHA-256 entries must match |
| API17/ARMv7 build + zipalign/signature/DEX/manifest checks | Old Android packaging and permanent certificate; not decoder/UI execution |
| `make test-features` | Real PBKDF2/vault authentication/tamper/atomic-save tests, all 18 RFC6238 vectors, Proton/Bitwarden CSV/JSON import fixtures and real QR-share HTTP auth/CSRF/path/upload/download/range/stop tests, plus key grammar, player/collection/pin parsers and real MP4/M4A downloads |
| `make test-discovery` | Production podcast favorite persistence/filtering/URL validation, safe failure/capacity cases and two-row header allocations/events at 320/360/600/1024dp using host preferences/layout fakes; not physical Android rendering |
| Artist discovery in `make test-features` | Actual filtered request payloads, two-row/responsive artist identities, no song fallback, exact public artist browse, scoped songs/albums/singles and first-page bounds |
| `make test-optical` | Actual Java/browser QR encoders and camera-luma readers, byte-for-byte cross-language reassembly, rotation/mirror/noise, CRC/SHA, missing/repeated/out-of-order frames, path/size/collision/cancel bounds; not a physical camera test |
| `make test-glass` | Production lens layers/alpha/pressed states, bounded cached wallpaper frost and screen alignment with recording Android graphics fakes; not GPU screenshots |
| AAC compatibility/runtime | Actual esds/ASC validation and normalization, MIME lies/HE-AAC rejection, sample-exact ADTS recovery, compiled Music-before-HLS binding and cancellation |
| Download controller fakes | Actual progress/cancel/overlap/destroy logic; not Android window rendering |
| Host FFmpeg | Synthetic downloaded/remuxed H.264/AAC decodes, with explicit audio mapping and non-silent PCM checks for HLS/DASH recovery; not the Kobo hardware decoder |
| PC input mocks + framed sockets | Windows/Mac/X11 mapping/error paths, overlapping holds/rollback/disconnect cleanup; no real keyboard events emitted |
| `make test-fdroid` | Pinned cert retention, idempotent actual DEX/resource edits, generated Java migration + real host SQLite including credentials/failure cases |
| `make test-weather` | Real production models/cache/service/widget/bridge logic with synthetic providers and Android fakes |
| `make test-install` / `--apks` | Safe update policy, fake ADB flows, rootless status and byte-exact reconstruction of all 38 payloads; not Android installer dialogs |
| Runtime/isolation/full verifier | Media protocol/ownership/cancellation, routing, proxy cleanup, companion engines and earlier suite regressions |

No physical Kobo, emulator, real Windows/Mac/X11 key input, live YouTube/CBC/weather catalog
session, ROM flash or Bluetooth PAN hardware was used for this feature validation. This is
not a security audit. Provider availability, authentication restrictions, ROM permissions,
real signature-protected broadcasts, widgets, audio volume/focus and device performance
still require testing. Live TLS tests, where run, only establish the tested TLS endpoints.

### Kobo acceptance checklist

- Update in place from the earlier suite; confirm package data/certificate and all 38 patch
  report results. Test one cancelled rootless install and a storage/signature failure safely.
  PC scripts must not force PATCHES; a fresh launch uses the normal main tab and an
  existing user-selected tab may be retained.
- Sync F-Droid archives; compare All/Installed/Updates and an app known not to be installed.
  Restart, retry a failed sync and verify custom repositories/pins/credentials remain.
- Open Radio, Podcasts and News independently. Test both CBC regional buttons, a stream
  failure/timeout, rapid station changes and actual screen-off audio/owned notifications.
- Favorite two podcast shows, filter by title/publisher, clear, restart and filter offline.
  Reconnect to open episodes, remove one favorite, and leave during a directory/feed request.
- Check YouTube/Music headers in portrait/landscape and with larger text/keyboard visible.
  Submit via Go and keyboard Search; confirm all More actions still open. Search **Artists**,
  open a real artist's songs/album, return to results and switch modes during a pending request.
- Install this build's YouTube.apk and play an accessible video/Short and Music song.
  Use Sound to confirm media volume and the prepared audio-check result; listen on the
  speaker and headphones/Bluetooth as available. Test pause/seek/stop, audio-focus loss,
  notifications, screen-off, a real unsupported stream and a real sign-in/bot denial.
- Download video and M4A, play both offline, cancel mid-transfer, navigate away, exhaust
  free space safely and confirm no broken partial file appears as a completed download.
- Pin/unpin two real channels; restart. Open real Music album/community playlists, save/
  remove collections, create ordered local playlists and switch shelves rapidly during search.
- Add Weather and legacy suite widgets; change city/units, refresh, go offline, reboot and
  check explicit high/low, timestamps, size and stale/error labels on this launcher/ROM.
- Assign and use a real PC chord, overlapping momentary buttons and a disconnected/failed
  backend. Verify no held modifiers remain; do not test against an important active document.
- Use only disposable vault/2FA fixtures first: wrong password, background/idle lock,
  screenshots, clipboard ownership/expiry, rotation, cancelled derivation, encrypted export/
  restore, password change and interrupted saves. Compare a TOTP with a trusted authenticator.
  Also test Proton Pass/Bitwarden plaintext CSV/JSON selection, preview, duplicate skipping and
  invalid/encrypted-export warnings; verify no entries are replaced.
- Check Home's glass dock/top capsule over several wallpapers, all skins/opacity modes,
  pressed-state/text contrast and scrolling/battery behavior. Transfer a disposable file in
  both optical directions with Wi-Fi disabled; miss/replay frames, cancel, retry front-camera
  focus/portrait/lighting and compare the saved file's hash. Test duplicate-name saves.
- On a trusted LAN, test QR share confirmation, QR/link access, upload/download, duplicate-name
  handling, manual stop and automatic expiry. Do not use sensitive files or an untrusted network.

## Windows launchers / Bluetooth Internet retirement

Download and **Extract All** from the matching `Windows-Tools.zip`; see
[Windows instructions](../tools/WINDOWS-TOOLS.md). All six tracked `.bat` files share a pinned
PowerShell helper: no working-directory/apostrophe interpolation, Python required only for
Stream Deck, no application-failure interpreter retry, no silent successful exit on errors.
Wi-Fi is read-only/manifest-driven with ranges; USB updates preserve data; backups stream,
check supplied hashes, safely extract and clean up their tunnel. Fake-device Windows CI is
separate from actual hardware validation.

Bluetooth Internet is no longer built, registered, listed or bundled. The standalone QR
Transfer app replaces its catalog slot, not its tethering behavior. No old installation is
silently uninstalled; old pinned Bluetooth tokens open Android Bluetooth settings.
