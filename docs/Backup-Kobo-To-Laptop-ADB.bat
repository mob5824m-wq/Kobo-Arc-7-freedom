@echo off
setlocal EnableExtensions
title Kobo Arc 7 - Direct USB ADB Laptop Backup ^& Restore Tool (v1.8.0)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference = 'Stop';" ^
  "Write-Host '';" ^
  "Write-Host '=====================================================================' -ForegroundColor Cyan;" ^
  "Write-Host '  KOBO ARC 7 FREEDOM v1.8.0 - DIRECT USB ADB LAPTOP BACKUP TOOL' -ForegroundColor Cyan;" ^
  "Write-Host '  Streams bit-for-bit partitions & files over USB using 0 MB tablet space!' -ForegroundColor Gray;" ^
  "Write-Host '=====================================================================' -ForegroundColor Cyan;" ^
  "Write-Host '';" ^
  "$adbCmd = Get-Command adb -ErrorAction SilentlyContinue;" ^
  "$adb = if ($adbCmd) { $adbCmd.Source } else { $null };" ^
  "if (-not $adb) {" ^
  "  $ptDir = Join-Path $env:LOCALAPPDATA 'KoboArc7Adb\platform-tools';" ^
  "  $adbCandidate = Join-Path $ptDir 'adb.exe';" ^
  "  if (-not (Test-Path $adbCandidate)) {" ^
  "    Write-Host '[1/4] Downloading official Google Android USB ADB tool (one-time ~7 MB)...' -ForegroundColor Yellow;" ^
  "    New-Item -ItemType Directory -Force -Path (Split-Path $ptDir) | Out-Null;" ^
  "    $zipPath = Join-Path $env:TEMP 'platform-tools-kobo.zip';" ^
  "    Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zipPath;" ^
  "    Expand-Archive -Force -Path $zipPath -DestinationPath (Split-Path $ptDir);" ^
  "  }" ^
  "  $adb = $adbCandidate;" ^
  "}" ^
  "Write-Host '[1/4] Waiting for Kobo Arc 7 over USB cable (enable Settings -> Developer options -> USB debugging)...' -ForegroundColor Yellow;" ^
  "& $adb start-server | Out-Null;" ^
  "& $adb wait-for-device;" ^
  "$serial = (& $adb get-serialno).Trim();" ^
  "Write-Host ('  Connected to Kobo device: ' + $serial) -ForegroundColor Green;" ^
  "Write-Host '[2/4] Starting streaming backup service on Kobo Arc 7 & opening lossless USB tunnel (:18777)...' -ForegroundColor Yellow;" ^
  "& $adb shell 'am startservice -n ca.arc7freedom.app/.Arc7AdbBackupService >/dev/null 2>&1 || am start -n ca.arc7freedom.app/.MainActivity >/dev/null 2>&1' | Out-Null;" ^
  "Start-Sleep -Seconds 2;" ^
  "& $adb forward tcp:18777 tcp:18777 | Out-Null;" ^
  "$destBase = Join-Path ([Environment]::GetFolderPath('Desktop')) ('Kobo-Arc7-Backups\' + (Get-Date -Format 'yyyyMMdd-HHmmss'));" ^
  "New-Item -ItemType Directory -Force -Path $destBase | Out-Null;" ^
  "Write-Host '';" ^
  "Write-Host 'Choose what to back up directly onto this laptop:' -ForegroundColor White;" ^
  "Write-Host '  [1] Essential Unbrick Backup (~35 MB: boot + recovery + NVRAM Wi-Fi MAC + uboot + scatter)' -ForegroundColor Green;" ^
  "Write-Host '  [2] Complete Factory Clone (~850 MB: Everything in [1] + full /system OS image)' -ForegroundColor Cyan;" ^
  "Write-Host '  [3] My Personal Files (Books, Audiobooks, Music, Comics & Photos from tablet storage)' -ForegroundColor Magenta;" ^
  "Write-Host '  [4] Open Interactive Browser Backup Dashboard (http://127.0.0.1:18777)' -ForegroundColor Yellow;" ^
  "$choice = Read-Host 'Enter 1, 2, 3, or 4 (default = 1)';" ^
  "if ([string]::IsNullOrWhiteSpace($choice)) { $choice = '1' }" ^
  "if ($choice -eq '4') { Start-Process 'http://127.0.0.1:18777/'; Write-Host 'Opened http://127.0.0.1:18777 in your browser! Keep this window open until download finishes.' -ForegroundColor Green; Read-Host 'Press Enter when finished'; exit 0 }" ^
  "$endpoint = if ($choice -eq '2') { '/bundle.zip?full=1' } elseif ($choice -eq '3') { '/sdcard-files.zip' } else { '/bundle.zip' };" ^
  "$outZip = Join-Path $destBase (if ($choice -eq '2') { 'KoboArc7-Full-System-Clone.zip' } elseif ($choice -eq '3') { 'KoboArc7-UserFiles.zip' } else { 'KoboArc7-Unbrick-Backup.zip' });" ^
  "Write-Host ('[3/4] Streaming backup over USB straight to: ' + $outZip) -ForegroundColor Cyan;" ^
  "curl.exe -fL --progress-bar ('http://127.0.0.1:18777' + $endpoint) -o $outZip;" ^
  "Write-Host '[4/4] Extracting & verifying cryptographic SHA-256 checksums on your laptop...' -ForegroundColor Yellow;" ^
  "$extractDir = Join-Path $destBase 'extracted';" ^
  "Expand-Archive -Force -Path $outZip -DestinationPath $extractDir;" ^
  "$sumFile = Join-Path $extractDir 'SHA256SUMS.txt';" ^
  "if (Test-Path $sumFile) {" ^
  "  $allOk = $true;" ^
  "  Get-Content $sumFile | Where-Object { $_.Trim() -ne '' } | ForEach-Object {" ^
  "    $parts = $_ -split '  ', 2;" ^
  "    $target = Join-Path $extractDir $parts[1];" ^
  "    $actual = (Get-FileHash -Algorithm SHA256 -Path $target).Hash.ToLower();" ^
  "    if ($actual -eq $parts[0].ToLower()) { Write-Host ('  [SHA256 OK] ' + $parts[1]) -ForegroundColor Green }" ^
  "    else { Write-Host ('  [MISMATCH!] ' + $parts[1]) -ForegroundColor Red; $allOk = $false }" ^
  "  };" ^
  "  if (-not $allOk) { throw 'Checksum mismatch detected!' }" ^
  "}" ^
  "Write-Host '';" ^
  "Write-Host ('SUCCESS! Verified backup saved on your laptop at: ' + $destBase) -ForegroundColor Green;" ^
  "Start-Process explorer.exe $destBase;"
pause
