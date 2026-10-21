<# :
@echo off
setlocal
title Kobo Arc 7 Freedom - Zero-SSL Home Wi-Fi Sideload Server (:8000)
powershell -NoProfile -ExecutionPolicy Bypass -Command "& ([ScriptBlock]::Create((Get-Content -LiteralPath '%~f0' -Raw))) -ScriptDir '%~dp0'"
pause
exit /b
#>
param([string]$ScriptDir = "")

$ErrorActionPreference = "Continue"
if ([string]::IsNullOrWhiteSpace($ScriptDir)) {
    $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
}
$ScriptDir = $ScriptDir.TrimEnd('\', '/')

# Locate repo `releases\` folder if present; otherwise create standalone `Kobo-WiFi-Server` folder next to this .bat!
$RepoReleases = Join-Path (Split-Path -Parent $ScriptDir) "releases"
if (Test-Path (Join-Path $RepoReleases "KoboArc7Freedom.apk")) {
    $ServeDir = $RepoReleases
} elseif (Test-Path (Join-Path $ScriptDir "KoboArc7Freedom.apk")) {
    $ServeDir = $ScriptDir
} else {
    $ServeDir = Join-Path $ScriptDir "Kobo-WiFi-Files"
    New-Item -ItemType Directory -Force -Path $ServeDir | Out-Null
}

Write-Host ""
Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "  Kobo Arc 7 Freedom - 1-Click Windows Home Wi-Fi Server (Zero SSL!)" -ForegroundColor Cyan
Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "Serving folder: $ServeDir" -ForegroundColor Gray

# Auto-download latest signed APKs from GitHub Releases if run standalone in Downloads!
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$NeededApks = @(
    @{ Name = "KoboArc7Freedom.apk"; Url = "https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/KoboArc7Freedom.apk" },
    @{ Name = "VanillaAndroid4Home.apk"; Url = "https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/VanillaAndroid4Home.apk" }
)
foreach ($item in $NeededApks) {
    $dest = Join-Path $ServeDir $item.Name
    if (-not (Test-Path $dest) -or ((Get-Item $dest).Length -lt 10240)) {
        Write-Host "Downloading $($item.Name) from GitHub Releases..." -ForegroundColor Yellow
        try {
            Invoke-WebRequest -Uri $item.Url -OutFile $dest -UseBasicParsing
            Write-Host "  Saved $($item.Name) ($([Math]::Round((Get-Item $dest).Length / 1MB, 2)) MB)" -ForegroundColor Green
        } catch {
            Write-Host "  ERROR downloading $($item.Name): $($_.Exception.Message)" -ForegroundColor Red
        }
    }
}

# Ensure Windows Defender Firewall allows inbound LAN TCP 8000 (Public & Private profiles!)
$RuleName = "Kobo Arc 7 Wi-Fi Installer (TCP 8000)"
$ExistingRule = netsh advfirewall firewall show rule name="$RuleName" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Opening Windows Defender Firewall port 8000 so Kobo Arc 7 can connect over Wi-Fi..." -ForegroundColor Yellow
    $fwCmd = "/c netsh advfirewall firewall add rule name=`"$RuleName`" dir=in action=allow protocol=TCP localport=8000 profile=any & netsh advfirewall firewall add rule name=`"Kobo Arc 7 Wi-Fi Installer (TCP 8080)`" dir=in action=allow protocol=TCP localport=8080 profile=any"
    try {
        Start-Process -FilePath "cmd.exe" -ArgumentList $fwCmd -Verb RunAs -WindowStyle Hidden -Wait -ErrorAction Stop
        Write-Host "  Windows Firewall TCP 8000 & 8080 unlocked for home Wi-Fi!" -ForegroundColor Green
    } catch {
        Write-Host "  Note: Firewall prompt skipped. If tablet times out, allow TCP port 8000 in Windows Firewall." -ForegroundColor DarkYellow
    }
}

# Enumerate real LAN IPv4 addresses (skip WSL, Hyper-V, Docker, VPN, APIPA 169.254.*, Loopback)
$LanIps = @()
try {
    $adapters = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop | Where-Object {
        $_.IPAddress -notlike "127.*" -and
        $_.IPAddress -notlike "169.254.*" -and
        $_.InterfaceAlias -notmatch "WSL|Hyper-V|vEthernet|Docker|Loopback|Tailscale|WireGuard|TAP|ZeroTier"
    } | Sort-Object -Property @{Expression = { if ($_.InterfaceAlias -match "Wi-Fi|Wireless|WLAN") { 0 } else { 1 } }}
    foreach ($a in $adapters) {
        if ($LanIps -notcontains $a.IPAddress) { $LanIps += $a.IPAddress }
    }
} catch {}
if ($LanIps.Count -eq 0) {
    [System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) | Where-Object {
        $_.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork -and
        $_.IPAddressToString -notlike "127.*" -and $_.IPAddressToString -notlike "169.254.*"
    } | ForEach-Object { $LanIps += $_.IPAddressToString }
}
if ($LanIps.Count -eq 0) { $LanIps = @("127.0.0.1") }

# Prefer Python server if available; otherwise run built-in native .NET TcpListener HTTP/1.1 server!
$PyScript = Join-Path $ScriptDir "serve-over-wifi.py"
if (Test-Path $PyScript) {
    foreach ($py in @("py", "python", "python3")) {
        $cmd = Get-Command $py -ErrorAction SilentlyContinue
        if ($cmd -and $cmd.Source -notmatch "WindowsApps") {
            & $py $PyScript
            exit $LASTEXITCODE
        }
    }
}

# Built-in Zero-Python .NET TcpListener HTTP/1.1 Server
$Listener = $null
$Port = 8000
foreach ($p in @(8000, 8080, 8888)) {
    try {
        $l = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $p)
        $l.Server.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::Socket, [System.Net.Sockets.SocketOptionName]::ReuseAddress, $true)
        $l.Start()
        $Listener = $l
        $Port = $p
        break
    } catch {}
}
if (-not $Listener) {
    Write-Host "ERROR: Could not bind TCP port 8000/8080/8888." -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "  READY! On your Kobo Arc 7 tablet:" -ForegroundColor Green
Write-Host "  1. Enable Settings -> Security -> Unknown sources"
Write-Host "  2. Open Kobo's Browser and type this exact PLAIN HTTP address:"
Write-Host ""
foreach ($ip in $LanIps) {
    Write-Host "         http://${ip}:${Port}" -ForegroundColor White -BackgroundColor DarkBlue
}
Write-Host ""
Write-Host "  Keep this window open until KoboArc7Freedom.apk finishes downloading!" -ForegroundColor Yellow
Write-Host "======================================================================" -ForegroundColor Cyan

while ($true) {
    $client = $Listener.AcceptTcpClient()
    try {
        $client.ReceiveTimeout = 20000
        $client.SendTimeout = 60000
        $stream = $client.GetStream()
        $first = $stream.ReadByte()
        # Reject TLS ClientHello (0x16 / 0x80) immediately so Chrome/Safari HTTPS-First falls back to http://!
        if ($first -lt 0 -or $first -eq 0x16 -or $first -eq 0x80) {
            $client.Close()
            continue
        }
        $ms = New-Object System.IO.MemoryStream
        $ms.WriteByte([byte]$first)
        $p3 = 0; $p2 = 0; $p1 = $first
        while ($ms.Length -lt 16384) {
            $b = $stream.ReadByte()
            if ($b -lt 0) { break }
            $ms.WriteByte([byte]$b)
            if ($p3 -eq 13 -and $p2 -eq 10 -and $p1 -eq 13 -and $b -eq 10) { break }
            $p3 = $p2; $p2 = $p1; $p1 = $b
        }
        $reqText = [System.Text.Encoding]::ASCII.GetString($ms.ToArray())
        $reqLine = ($reqText -split "`r`n")[0]
        $parts = $reqLine -split " "
        $method = $parts[0]
        $rawUrl = if ($parts.Length -ge 2) { $parts[1] } else { "/" }
        $cleanPath = ($rawUrl -split "\?")[0]
        $forceRaw = $false
        if ($cleanPath.StartsWith("/raw/")) {
            $forceRaw = $true
            $cleanPath = "/" + $cleanPath.Substring(5)
        }
        $decodedName = [System.Uri]::UnescapeDataString($cleanPath.TrimStart('/'))

        if ([string]::IsNullOrEmpty($decodedName) -or $decodedName -eq "index.html") {
            $btns = ""
            Get-ChildItem -File $ServeDir | Sort-Object Name | ForEach-Object {
                $mb = [Math]::Round($_.Length / 1MB, 2)
                $enc = [System.Uri]::EscapeDataString($_.Name)
                $cls = if ($_.Name -eq "KoboArc7Freedom.apk") { "btn primary" } else { "btn" }
                $btns += "<a class='$cls' href='./$enc'>Download $($_.Name) ($mb MB)</a>"
                if ($_.Name.EndsWith(".apk")) {
                    $btns += "<a class='btn alt' href='./raw/$enc'>Alternate Direct Binary Download: $($_.Name) (use if Kobo says Cannot Download)</a>"
                }
            }
            $html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                    "<title>Kobo Arc 7 Wi-Fi Installer</title>" +
                    "<style>body{font-family:sans-serif;background:#0e1319;color:#f8fafc;padding:20px;margin:0;}" +
                    ".card{background:#161e28;border:1px solid #2b394b;border-radius:10px;padding:20px;max-width:660px;margin:0 auto;}" +
                    "h1{color:#38bdf8;font-size:21px;margin:0 0 10px 0;}p,li{color:#cbd5e1;line-height:1.5;font-size:15px;}" +
                    ".btn{display:block;background:#1f2a38;color:#fff;text-decoration:none;padding:15px 18px;border-radius:8px;margin:10px 0;font-weight:bold;border:1px solid #475569;text-align:center;}" +
                    ".primary{background:#0284c7;border-color:#7dd3fc;font-size:18px;}.alt{background:#0f172a;font-size:13px;color:#93c5fd;}</style>" +
                    "</head><body><div class='card'><h1>Kobo Arc 7 Freedom — Local Wi-Fi Installer</h1>" +
                    "<ol><li>Enable <b>Settings &rarr; Security &rarr; Unknown sources</b> on your tablet.</li>" +
                    "<li>Tap the blue button below to download <b>KoboArc7Freedom.apk</b> over Wi-Fi.</li>" +
                    "<li>Swipe down from the <b>top-left corner</b> of your Kobo screen and tap <b>KoboArc7Freedom.apk</b> to install!</li></ol>" +
                    $btns + "</div></body></html>"
            $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($html)
            $hdr = "HTTP/1.1 200 OK`r`nContent-Type: text/html; charset=utf-8`r`nContent-Length: $($bodyBytes.Length)`r`nCache-Control: no-store`r`nConnection: close`r`n`r`n"
            $hdrBytes = [System.Text.Encoding]::ASCII.GetBytes($hdr)
            $stream.Write($hdrBytes, 0, $hdrBytes.Length)
            if ($method -ne "HEAD") { $stream.Write($bodyBytes, 0, $bodyBytes.Length) }
        } else {
            $safeName = [System.IO.Path]::GetFileName($decodedName)
            $filePath = Join-Path $ServeDir $safeName
            if (Test-Path -LiteralPath $filePath -PathType Leaf) {
                $fi = Get-Item -LiteralPath $filePath
                $mime = if ($forceRaw) { "application/octet-stream" } elseif ($safeName.ToLower().EndsWith(".apk")) { "application/vnd.android.package-archive" } else { "application/octet-stream" }
                $hdr = "HTTP/1.1 200 OK`r`nContent-Type: $mime`r`nContent-Length: $($fi.Length)`r`nAccept-Ranges: none`r`nContent-Disposition: attachment; filename=`"$safeName`"`r`nConnection: close`r`n`r`n"
                $hdrBytes = [System.Text.Encoding]::ASCII.GetBytes($hdr)
                $stream.Write($hdrBytes, 0, $hdrBytes.Length)
                if ($method -ne "HEAD") {
                    Write-Host "  -> Sending $safeName ($([Math]::Round($fi.Length / 1MB, 2)) MB) to $($client.Client.RemoteEndPoint)..." -ForegroundColor Cyan
                    $fs = [System.IO.File]::OpenRead($filePath)
                    try {
                        $buf = New-Object byte[] 65536
                        while (($read = $fs.Read($buf, 0, $buf.Length)) -gt 0) {
                            $stream.Write($buf, 0, $read)
                        }
                        Write-Host "  [100% DELIVERED] $safeName finished transferring to tablet!" -ForegroundColor Green
                    } finally {
                        $fs.Close()
                    }
                }
            } else {
                $err = [System.Text.Encoding]::UTF8.GetBytes("Not found")
                $hdr = [System.Text.Encoding]::ASCII.GetBytes("HTTP/1.1 404 Not Found`r`nContent-Length: $($err.Length)`r`nConnection: close`r`n`r`n")
                $stream.Write($hdr, 0, $hdr.Length)
                if ($method -ne "HEAD") { $stream.Write($err, 0, $err.Length) }
            }
        }
        $stream.Flush()
    } catch {
        # Normal socket abort when Android Browser hands download off to DownloadManager
    } finally {
        $client.Close()
    }
}
