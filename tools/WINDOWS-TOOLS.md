# Windows tools — extract the ZIP first

Download **Windows-Tools.zip** from the same PR build artifact or release as your APKs.
Right-click → **Extract All**, then run the desired `.bat`. Do not run it inside the ZIP
viewer. Windows 10/11's built-in **Windows PowerShell 5.1** is sufficient; administrator
access and Python are **not** needed for Wi-Fi serving, USB installation or USB backup.
The PC Stream Deck host separately needs **Python 3.9+** and its documented dependencies.

The five launchers (and the Stream Deck copy bundled on the Kobo) share a matching,
SHA-256-pinned `Arc7-Windows.ps1`. Keep that helper beside them. A `.bat` downloaded alone
can fetch its matching helper from the latest GitHub release; if the release and launcher
are different builds, it **stops** and asks for the matching ZIP instead of running
unverified or stale code. PR builds should use their complete ZIP; a PR is not a release.

| Launcher | What it does |
| --- | --- |
| `Start-Kobo-WiFi-Server.bat` | Read-only LAN installer, normally TCP 8000. Shows APKs from the release checksum manifest, including QR Transfer. No manually maintained app list. |
| `serve-over-wifi.bat` | Same native Windows read-only Wi-Fi server; no Python/WSL or HttpListener administrator URL reservation. |
| `usb-sideload-and-root.bat` | Finds ADB, selects one authorized device, installs the suite **in place** and opens it. The historical filename does not imply automatic rooting; review PATCHES on the Kobo. |
| `Backup-Kobo-To-Laptop-ADB.bat` | Performs the same safe update, starts the Kobo backup service and streams the selected ZIP directly to the PC. Verifies supplied SHA-256 entries and safely extracts it. Root is required for protected partitions, not for running this PC tool. |
| `Start-PC-Stream-Deck.bat` | Finds a working Python 3.9+ interpreter, then launches the host **once**, forwarding arguments. An application failure is not retried with another interpreter. |

Paths with spaces, apostrophes, parentheses, `&` and `!` are supported; the current working
directory does not need to be the tools folder. Errors stay visible and return a nonzero
exit code. Press a key to close the window after a tool exits.

## Command prompt examples

```bat
Start-Kobo-WiFi-Server.bat -Port 8000 -Root "C:\Kobo build\releases"
serve-over-wifi.bat -Port 8088 -Root "D:\Kobo files" -NoBrowser
usb-sideload-and-root.bat -Serial YOUR_KOBO_SERIAL -Apk "C:\Kobo build\KoboArc7Freedom.apk"
Backup-Kobo-To-Laptop-ADB.bat -Choice 1 -Output "D:\Kobo backups" -NoBrowser
Start-PC-Stream-Deck.bat --studio-port 18995
```

- **Wi-Fi:** put the matching `SHA256SUMS.txt` and APKs in `-Root` (APKs may also be in
  its `apps` subfolder). With a repository checkout the launcher finds `../releases`. Matching checksum-listed
  companions, including the offline QR webpage, can stay beside the launcher in `tools`;
  they do not need copying into the APK folder and unlisted files are never shared.
  Missing artifacts can be fetched from the current release and are hash-checked before
  serving. A local manifest is authoritative; mixed-build files are refused, not silently
  served. Use the same private LAN, use the displayed `http://PC_LAN_IP:PORT/` on the Kobo,
  and allow Windows Firewall on **Private networks only** if prompted. No firewall rule
  is silently installed. This intentionally unencrypted server is read-only; arbitrary
  PC folders and uploads are not exposed. Close the window or press Ctrl+C when finished.
- **USB:** enable USB debugging, use a data-capable cable, install your device's USB driver
  if needed and accept the Kobo's authorization prompt. Put official `platform-tools`
  beside the launcher or in PATH. If missing and online, the tool can download Google's
  official Windows platform-tools ZIP over HTTPS. Multiple devices require `-Serial`;
  unauthorized/offline devices and failed/signature-conflicting updates stop safely.
  There is **no** uninstall, data wipe, forced downgrade or automatic rooting fallback.
- **Backups:** choice 1 = essential, 2 = full system (root), 3 = personal files, 4 = browser
  dashboard. Keep choice 4's window open until downloads finish. A free ADB forwarding port
  is allocated, then removed when the tool exits. A valid ZIP and matching hashes verify
  transferred bytes, **not** the completeness/restorability of a hardware backup; inspect
  its device report and keep independent copies. Incomplete/error downloads are not
  reported as verified. ZIPs cannot extract outside their new extraction directory.
- **Stream Deck:** install Python from python.org with the Python launcher/PATH option.
  Microsoft Store aliases are probed rather than assumed to be working interpreters.
  The Python companion's errors are displayed; see its header for optional OBS/input
  dependencies. You may set `ARC7_PYTHON` to a portable `python.exe`.

## Offline / automation

Set `ARC7_OFFLINE=1` to prohibit bootstrap/release downloads; provide the matching helper,
manifest, artifacts and platform-tools locally. Set `ARC7_NO_PAUSE=1` for scripts/CI.
`ARC7_ADB` and `ARC7_PYTHON` can select explicit local executables. Existing APKs/data are
never deleted to work around update errors. Run `python scripts/build-windows-tools.py`
after changing the helper or companion sources; `--check` checks mirrors, ASCII/CRLF and
reproducible ZIP bytes.

The Windows CI job executes the real batch/PowerShell paths using **fake ADB/Python and a
local backup HTTP fixture**, including awkward paths, HTTP ranges, download hash refusal,
failed updates and interpreter errors. That is not a physical Windows-to-Kobo USB, camera,
firewall or rooting test. Linux source/package checks do not substitute for this job.
