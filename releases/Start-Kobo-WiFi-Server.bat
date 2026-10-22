<# : batch portion
@echo off
setlocal
title Kobo Arc 7 Freedom v1.9.1 - Concurrent Home Wi-Fi OTA Rooting Server (:8000)
powershell -NoProfile -ExecutionPolicy Bypass -Command "& ([ScriptBlock]::Create((Get-Content -LiteralPath '%~f0' -Raw)))"
pause
exit /b
#>

$ErrorActionPreference = 'Continue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Port = 8000
$WorkDir = Join-Path $env:USERPROFILE "Downloads\Kobo-Arc-7-Freedom-WiFi"
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

# Copy local APKs first if user downloaded them alongside this .bat
$ScriptFolder = $PSScriptRoot
if (-not $ScriptFolder) { $ScriptFolder = (Get-Location).Path }
foreach ($localName in @("Upgrade-My-Kobo-To-v2.3.1.apk", "KoboArc7Freedom.apk", "VLC.apk", "AuroraStore.apk", "VanillaAndroid4Home.apk", "SHA256SUMS.txt")) {
    foreach ($candidateDir in @($ScriptFolder, (Join-Path $ScriptFolder "..\releases"))) {
        $cand = Join-Path $candidateDir $localName
        if (Test-Path -LiteralPath $cand) {
            Copy-Item -Force -LiteralPath $cand -Destination (Join-Path $WorkDir $localName)
        }
    }
}

$RawBase = "https://raw.githubusercontent.com/mob5824m-wq/Kobo-Arc-7-freedom/main/releases"
$ReleaseBase = "https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download"
$RequiredFiles = @(
    "Upgrade-My-Kobo-To-v2.3.1.apk",
    "KoboArc7Freedom.apk",
    "VLC.apk",
    "AuroraStore.apk",
    "VanillaAndroid4Home.apk",
    "SHA256SUMS.txt"
)

foreach ($f in $RequiredFiles) {
    $dst = Join-Path $WorkDir $f
    if (-not (Test-Path -LiteralPath $dst) -or ((Get-Item -LiteralPath $dst).Length -lt 512)) {
        Write-Host "==> Downloading $f ..." -ForegroundColor Cyan
        $ok = $false
        foreach ($base in @($RawBase, $ReleaseBase)) {
            try {
                Invoke-WebRequest -UseBasicParsing -Uri "$base/$f" -OutFile $dst -TimeoutSec 120
                if ((Test-Path -LiteralPath $dst) -and ((Get-Item -LiteralPath $dst).Length -gt 200)) {
                    $ok = $true
                    break
                }
            } catch {}
        }
        if (-not $ok) {
            Write-Warning "Could not auto-download $f — check your internet connection."
        }
    }
}

# Best-effort Windows Firewall open on TCP 8000 (silent if non-admin)
try {
    netsh advfirewall firewall add rule name="Kobo Arc 7 Wi-Fi OTA 8000" dir=in action=allow protocol=TCP localport=$Port 2>$null | Out-Null
} catch {}

# Detect real LAN IPv4 addresses (excluding loopback, APIPA, WSL/Hyper-V virtual NICs)
$Ips = @()
try {
    $Ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike '127.*' -and
            $_.IPAddress -notlike '169.254.*' -and
            $_.InterfaceAlias -notmatch 'WSL|vEthernet|Loopback|VMware|VirtualBox'
        } |
        Select-Object -ExpandProperty IPAddress -Unique
} catch {}
if (-not $Ips -or $Ips.Count -eq 0) {
    $Ips = [System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) |
        Where-Object { $_.AddressFamily -eq 'InterNetwork' -and $_.IPAddressToString -notlike '127.*' } |
        ForEach-Object { $_.IPAddressToString }
}

$Listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $Port)
$Listener.Start()

Write-Host ""
Write-Host "========================================================================" -ForegroundColor Green
Write-Host "  KOBO ARC 7 FREEDOM v1.9.1 - CONCURRENT WI-FI OTA ROOTING SERVER LIVE!" -ForegroundColor Green
Write-Host "========================================================================" -ForegroundColor Green
Write-Host "  1. On Kobo Arc 7, enable: Settings -> Security -> Unknown sources"
Write-Host "  2. Open Kobo's Browser and type this exact address (with http://):"
foreach ($ip in $Ips) {
    Write-Host ""
    Write-Host "       http://${ip}:${Port}" -ForegroundColor Yellow
}
Write-Host ""
Write-Host "  3. Tap 'KoboArc7Freedom.apk' (new root) or 'Upgrade-My-Kobo-To-v1.9.1.apk'!"
Write-Host "========================================================================" -ForegroundColor Green
Write-Host ""

$ClientHandler = {
    param($Client, $ServeRoot)
    try {
        $Client.ReceiveTimeout = 15000
        $Client.SendTimeout = 120000
        $Stream = $Client.GetStream()

        # Read HTTP request header lines
        $HeaderBuf = New-Object System.Collections.Generic.List[byte]
        $b = 0
        $FirstByteChecked = $false
        while ($HeaderBuf.Count -lt 16384) {
            $b = $Stream.ReadByte()
            if ($b -lt 0) { return }
            if (-not $FirstByteChecked) {
                $FirstByteChecked = $true
                # Immediately reject accidental TLS ClientHello (0x16 / 0x80) so HTTPS-First falls back to HTTP in <1ms!
                if ($b -eq 0x16 -or $b -eq 0x80) { return }
            }
            $HeaderBuf.Add([byte]$b)
            $cnt = $HeaderBuf.Count
            if ($cnt -ge 4 -and
                $HeaderBuf[$cnt-4] -eq 13 -and $HeaderBuf[$cnt-3] -eq 10 -and
                $HeaderBuf[$cnt-2] -eq 13 -and $HeaderBuf[$cnt-1] -eq 10) {
                break
            }
        }

        $ReqText = [System.Text.Encoding]::ASCII.GetString($HeaderBuf.ToArray())
        $Lines = $ReqText -split "`r`n"
        if ($Lines.Count -lt 1) { return }
        $Parts = $Lines[0] -split ' '
        if ($Parts.Count -lt 2) { return }
        $Method = $Parts[0].ToUpperInvariant()
        $RawUrl = $Parts[1]
        $CleanPath = [Uri]::UnescapeDataString(($RawUrl -split '\?')[0]).TrimStart('/')
        $ForceOctet = $false
        if ($CleanPath.StartsWith("raw/")) {
            $ForceOctet = $true
            $CleanPath = $CleanPath.Substring(4)
        }
        $SafeName = [System.IO.Path]::GetFileName($CleanPath)

        $RangeHeader = $null
        foreach ($ln in $Lines) {
            if ($ln -match '^Range:\s*bytes=(\d*)-(\d*)') {
                $RangeHeader = $Matches
            }
        }

        if ([string]::IsNullOrWhiteSpace($SafeName) -or $SafeName -eq "index.html") {
            $Rows = ""
            foreach ($item in Get-ChildItem -LiteralPath $ServeRoot -File | Sort-Object Name) {
                $mb = "{0:N2} MB" -f ($item.Length / 1MB)
                $cls = if ($item.Name -match '^Upgrade-My-Kobo|^KoboArc7Freedom') { "btn primary" } else { "btn" }
                $Rows += "<a class='$cls' href='./$($item.Name)'>Download $($item.Name) ($mb)</a>"
                if ($item.Extension -eq ".apk") {
                    $Rows += "<a class='sub' href='./raw/$($item.Name)'>Alternate Direct Binary Stream ($($item.Name))</a>"
                }
            }
            $Html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Kobo Arc 7 v1.9.1 OTA Portal</title><style>body{font-family:sans-serif;background:#0b1017;color:#f8fafc;padding:18px;}.card{background:#151e29;border:1px solid #2b3b4e;border-radius:12px;padding:18px;max-width:660px;margin:auto;}h1{color:#38bdf8;font-size:20px;margin-top:0;}.btn{display:block;background:#1f2a38;color:#fff;text-decoration:none;padding:15px;border-radius:9px;margin:10px 0 4px 0;font-weight:bold;border:1px solid #475569;text-align:center;}.primary{background:#0284c7;border-color:#7dd3fc;font-size:17px;}.sub{display:block;color:#93c5fd;text-align:center;font-size:12.5px;margin-bottom:10px;}</style></head><body><div class='card'><h1>Kobo Arc 7 Freedom v1.9.1 — Wi-Fi OTA Rooting &amp; Installer</h1><p>1. Enable <b>Settings &rarr; Security &rarr; Unknown sources</b>.<br>2. Tap a blue button below to download directly over Wi-Fi.<br>3. Pull down the <b>top-left notification shade</b> on your Kobo and tap to install!</p>$Rows</div></body></html>"
            $BodyBytes = [System.Text.Encoding]::UTF8.GetBytes($Html)
            $Hdr = "HTTP/1.1 200 OK`r`nContent-Type: text/html; charset=utf-8`r`nContent-Length: $($BodyBytes.Length)`r`nConnection: close`r`nCache-Control: no-store`r`n`r`n"
            $HdrBytes = [System.Text.Encoding]::ASCII.GetBytes($Hdr)
            $Stream.Write($HdrBytes, 0, $HdrBytes.Length)
            if ($Method -ne "HEAD") {
                $Stream.Write($BodyBytes, 0, $BodyBytes.Length)
            }
            $Stream.Flush()
            return
        }

        $TargetFile = Join-Path $ServeRoot $SafeName
        if (-not (Test-Path -LiteralPath $TargetFile -PathType Leaf)) {
            $NotFound = [System.Text.Encoding]::ASCII.GetBytes("HTTP/1.1 404 Not Found`r`nContent-Length: 9`r`nConnection: close`r`n`r`nNot Found")
            $Stream.Write($NotFound, 0, $NotFound.Length)
            return
        }

        $FileInfo = Get-Item -LiteralPath $TargetFile
        $TotalLen = [int64]$FileInfo.Length
        $StartPos = [int64]0
        $EndPos = [int64]($TotalLen - 1)
        $StatusLine = "HTTP/1.1 200 OK"
        $RangeResp = ""

        if ($RangeHeader -ne $null -and $TotalLen -gt 0) {
            if ($RangeHeader[1].Length -gt 0) { $StartPos = [int64]$RangeHeader[1] }
            if ($RangeHeader[2].Length -gt 0) { $EndPos = [Math]::Min([int64]$RangeHeader[2], $TotalLen - 1) }
            if ($StartPos -le $EndPos -and $StartPos -lt $TotalLen) {
                $StatusLine = "HTTP/1.1 206 Partial Content"
                $RangeResp = "Content-Range: bytes $StartPos-$EndPos/$TotalLen`r`n"
            }
        }

        $SendLen = ($EndPos - $StartPos) + 1
        $Mime = if ($ForceOctet) { "application/octet-stream" } elseif ($SafeName.ToLower().EndsWith(".apk")) { "application/vnd.android.package-archive" } else { "text/plain; charset=utf-8" }
        $Hdr = "$StatusLine`r`nContent-Type: $Mime`r`nContent-Length: $SendLen`r`nAccept-Ranges: bytes`r`n${RangeResp}Content-Disposition: attachment; filename=`"$SafeName`"`r`nConnection: close`r`n`r`n"
        $HdrBytes = [System.Text.Encoding]::ASCII.GetBytes($Hdr)
        $Stream.Write($HdrBytes, 0, $HdrBytes.Length)

        if ($Method -ne "HEAD") {
            Write-Host "[OTA] Streaming $SafeName ($SendLen bytes) -> $($Client.Client.RemoteEndPoint)" -ForegroundColor Cyan
            $Fs = [System.IO.File]::Open($TargetFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
            try {
                $Fs.Position = $StartPos
                $Buf = New-Object byte[] 65536
                $Rem = $SendLen
                while ($Rem -gt 0) {
                    $Want = [int][Math]::Min([int64]$Buf.Length, $Rem)
                    $Read = $Fs.Read($Buf, 0, $Want)
                    if ($Read -le 0) { break }
                    $Stream.Write($Buf, 0, $Read)
                    $Rem -= $Read
                }
                $Stream.Flush()
                Write-Host "[OTA] Completed $SafeName!" -ForegroundColor Green
            } finally {
                $Fs.Close()
            }
        }
    } catch {
    } finally {
        try { $Client.Close() } catch {}
    }
}

$Pool = [RunspaceFactory]::CreateRunspacePool(1, 16)
$Pool.Open()

while ($true) {
    $TcpClient = $Listener.AcceptTcpClient()
    $Ps = [PowerShell]::Create()
    $Ps.RunspacePool = $Pool
    [void]$Ps.AddScript($ClientHandler).AddArgument($TcpClient).AddArgument($WorkDir)
    [void]$Ps.BeginInvoke()
}
