#!/usr/bin/env python3
"""Generate short ASCII/CRLF Windows launchers and a reproducible, complete ZIP.

No multi-line PowerShell -Command payloads, cmd/PowerShell polyglots, apostrophe
interpolation of paths, current-directory assumptions or application-failure fallback.
The only -Command is a small constant bootstrap reading paths from the environment;
it downloads a SHA-256-pinned helper if the matching local file is absent.
"""
from pathlib import Path
import hashlib
import io
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / "tools"
MODES = {
    "Start-Kobo-WiFi-Server.bat": ("wifi", "Kobo Arc 7 - Read-only Wi-Fi installer"),
    "serve-over-wifi.bat": ("wifi", "Kobo Arc 7 - Read-only Wi-Fi installer"),
    "Backup-Kobo-To-Laptop-ADB.bat": ("backup", "Kobo Arc 7 - In-place update and USB backup"),
    "usb-sideload-and-root.bat": ("install", "Kobo Arc 7 - Safe in-place USB installer"),
    "Start-PC-Stream-Deck.bat": ("streamdeck", "Kobo Arc 7 - PC Stream Deck"),
}


def generated():
    helper = (TOOLS / "Arc7-Windows.ps1").read_bytes()
    helper.decode("ascii")  # Windows PowerShell 5.1 must not misread UTF-8 source as ANSI.
    digest = hashlib.sha256(helper).hexdigest()
    output = {}
    for name, (mode, title) in MODES.items():
        text = f'''@echo off
setlocal EnableExtensions DisableDelayedExpansion
title {title}
set "ARC7_TOOL_ROOT=%~dp0"
set "ARC7_HELPER=%~dp0Arc7-Windows.ps1"
if not exist "%ARC7_HELPER%" set "ARC7_HELPER=%LOCALAPPDATA%\\KoboArc7Freedom\\tools\\{digest[:16]}\\Arc7-Windows.ps1"
set "ARC7_POWERSHELL=%SystemRoot%\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
if not exist "%ARC7_POWERSHELL%" set "ARC7_POWERSHELL=powershell.exe"
"%ARC7_POWERSHELL%" -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop';try{{$p=$env:ARC7_HELPER;if(-not(Test-Path -LiteralPath $p)){{if($env:ARC7_OFFLINE -eq '1'){{throw 'Missing matching Arc7-Windows.ps1. Extract the complete Windows-Tools.zip.'}};[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($p))|Out-Null;$t=$p+'.part';try{{Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Arc7-Windows.ps1' -OutFile $t -TimeoutSec 120;if((Get-FileHash -LiteralPath $t -Algorithm SHA256).Hash.ToLowerInvariant() -ne '{digest}'){{throw 'Helper checksum mismatch. Download the matching Windows-Tools.zip from the same build.'}};Move-Item -LiteralPath $t -Destination $p -Force}}finally{{if(Test-Path -LiteralPath $t){{Remove-Item -LiteralPath $t -Force}}}}}};if((Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant() -ne '{digest}'){{throw 'Launcher/helper version mismatch. Extract the matching Windows-Tools.zip.'}}}}catch{{Write-Host ('ERROR: '+$_.Exception.Message);exit 1}}"
if errorlevel 1 goto failed
"%ARC7_POWERSHELL%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%ARC7_HELPER%" -Tool {mode} %*
set "ARC7_RESULT=%ERRORLEVEL%"
goto done
:failed
set "ARC7_RESULT=1"
:done
if not defined ARC7_NO_PAUSE pause
endlocal & exit /b %ARC7_RESULT%
'''
        output[TOOLS / name] = text.replace("\n", "\r\n").encode("ascii")
    output[ROOT / "app/src/main/assets/chromebook/Start-PC-Stream-Deck.bat"] = output[TOOLS / "Start-PC-Stream-Deck.bat"]
    output[ROOT / "app/src/main/assets/chromebook/Arc7-Windows.ps1"] = helper
    files = {name: output[TOOLS / name] for name in MODES}
    for name in ("Arc7-Windows.ps1", "Start-PC-Stream-Deck.py", "Start-PC-School-VPN-Relay.py", "Start-Kobo-Second-Screen.py", "serve-over-wifi.py", "qr-transfer.html", "WINDOWS-TOOLS.md"):
        files[name] = (TOOLS / name).read_bytes()
    files["SHA256SUMS-WINDOWS.txt"] = "".join(f"{hashlib.sha256(data).hexdigest()}  {name}\n" for name, data in sorted(files.items())).encode("ascii")
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_STORED) as zf:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(name, (2020,1,1,0,0,0)); entry.compress_type = zipfile.ZIP_STORED
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            zf.writestr(entry, data)
    output[TOOLS / "Windows-Tools.zip"] = archive.getvalue()
    return output


def main():
    check = "--check" in sys.argv
    for path, data in generated().items():
        if check:
            if not path.is_file() or path.read_bytes() != data:
                raise SystemExit(f"Windows tools need regeneration: {path.relative_to(ROOT)}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
    print("PASS: Windows ASCII/CRLF launchers, pinned helper, embedded companion and complete reproducible ZIP" if check else "Generated Windows launchers and Windows-Tools.zip")


if __name__ == "__main__":
    main()
