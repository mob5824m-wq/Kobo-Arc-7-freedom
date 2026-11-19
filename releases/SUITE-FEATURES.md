# Arc7 Freedom 2.6.0 — app and runtime repairs

Native Android **4.0.3–4.2.2**, minSdk15 / targetSdk17, ARMv7, DEX035.
Suite, default apps, YouTube/Music and upgrader: **2.6.0 / 260**.
Home: **5.6.0 / 56**. Patched F-Droid: **1306**. Permanent signing identity retained.
This branch build is **not a newly published GitHub release**.

## Install without clearing data

1. Back up important data and stop playback. Install [KoboArc7Freedom.apk](KoboArc7Freedom.apk)
   **in place**, then open it once. Alternatively install the
   [2.6.0 upgrader](Upgrade-My-Kobo-To-v2.6.0.apk), which embeds exactly the same suite.
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

Shared cards, buttons and headers now use light gradient highlights, restrained rounded
edges and translucent glass-style surfaces. This is Android 4.x `GradientDrawable` styling,
**not real-time background blur or a claim of modern GPU effects**. The in-app Quick App
Bar Switcher is removed. Home's pinned dock, app slots, search button and other layout/
header controls remain; old switcher settings cannot turn it back on.

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

### YouTube and Shorts

**Pinned Channels** is explicitly local. Add a channel ID, public channel URL or handle;
view its public recent-video feed, or long-press to unpin. Pins survive restarts but are
not Google-account subscriptions, subscriber counts or push updates.

Provider bot/sign-in/age denials are terminal, visible access-required errors. Shorts
use the public video resolver and the same codec policy. **No authentication/bot bypass
is supplied or promised**; some Shorts/videos will remain unavailable on this device.

### YouTube Music

- **Quick play** uses local Liked Songs, otherwise loaded songs, otherwise one public-song
  search. It is bound to that request, not a late response from a different search.
- The search picker offers **Songs, Albums, Playlists and Community playlists**. A
  collection opens its own tracks; recommendations do not replace an unavailable playlist.
  Public remote collections load up to **60 tracks on the first page**, as shown in the UI.
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
  password autofill, QR scanning, HOTP or push authentication.
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

## Validation

**This build passed:** the complete signed build and full `scripts/verify-apks.py`
run, including the compiled MorpheEngine integration (not skipped), host FFmpeg,
new feature/catalog/Weather regressions and byte-exact reconstruction of all **38**
embedded APKs. All **52** release/app/tool checksums were independently checked.
This is host/binary evidence, not tablet playback or a security audit.

Suite SHA-256:
`2ee0ef1a09f66f79710fdfb3b9f1d4e5f59346d9b8268aaa2f0d48c7038f6460`

Upgrader SHA-256:
`d6b82cbbaf86784aea86faadb18a4744763fe46c0d4eb8f78b5b4c6b453f3a68`

Automated host and binary checks are recorded in the ignored build logs and run by `make verify`:

| Gate | What it establishes |
| --- | --- |
| API17/ARMv7 build + zipalign/signature/DEX/manifest checks | Old Android packaging and permanent certificate; not decoder/UI execution |
| `make test-features` | Real PBKDF2/vault authentication/tamper/atomic-save tests, all 18 RFC6238 vectors, key grammar, player/collection/pin parsers, synthetic HTTP ranges and real MP4/M4A downloads |
| Download controller fakes | Actual progress/cancel/overlap/destroy logic; not Android window rendering |
| Host FFmpeg | Synthetic downloaded/remuxed H.264/AAC media actually decodes; not the Kobo hardware decoder |
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
- Play an accessible YouTube video/Short and Music song, then test volume, pause/seek/stop,
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
