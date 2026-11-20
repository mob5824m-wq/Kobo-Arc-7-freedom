# Windows PowerShell 5.1+ / PowerShell 7. Called with -File, never a quoted ScriptBlock.
# Parse literal -File arguments ourselves. Advanced PowerShell parameter binding can
# consume/reject a Python --flag instead of forwarding it. Never evaluate CLI text.
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$Tool='wifi'; $Port=8000; $Root=''; $Apk=''; $Serial=''; $Output=''; $Choice=''
$NoBrowser=$false; $RemainingArgs=@(); $position=0
$arguments=@($args)
if ($arguments.Count -ge 2 -and $arguments[0] -eq '-Tool') { $Tool=$arguments[1]; $position=2 }
if ($Tool -notin @('wifi','install','backup','streamdeck')) { throw 'Unknown tool. Extract the complete matching Windows-Tools.zip.' }
if ($Tool -eq 'streamdeck') {
    if ($position -lt $arguments.Count) { $RemainingArgs=@($arguments[$position..($arguments.Count-1)]) }
} else {
    while ($position -lt $arguments.Count) {
        $option=$arguments[$position]; $position++
        if ($option -eq '-NoBrowser') { $NoBrowser=$true; continue }
        if ($position -ge $arguments.Count) { throw ('Missing value for ' + $option) }
        $value=$arguments[$position]; $position++
        switch ($option) {
            '-Port' { $number=0; if (-not [int]::TryParse($value,[ref]$number) -or $number -lt 1 -or $number -gt 65535) { throw 'Port must be between 1 and 65535.' }; $Port=$number }
            '-Root' { $Root=$value }
            '-Apk' { $Apk=$value }
            '-Serial' { $Serial=$value }
            '-Output' { $Output=$value }
            '-Choice' { if ($value -notin @('1','2','3','4')) { throw 'Backup choice must be 1, 2, 3 or 4.' }; $Choice=$value }
            default { throw ('Unknown argument ' + $option + '. See WINDOWS-TOOLS.md for supported options.') }
        }
    }
}
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ReleaseBase = 'https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download'
$ToolRoot = if ($env:ARC7_TOOL_ROOT) { $env:ARC7_TOOL_ROOT } else { $PSScriptRoot }
$Cache = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'KoboArc7Freedom'
$script:AdbPath = $null
$script:DeviceArgs = @()

function Get-Sha([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Get-ReleaseFile([string]$Name, [string]$Destination, [string]$Digest) {
    if ($Name -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') { throw 'Unsafe download filename.' }
    if ((Test-Path -LiteralPath $Destination) -and $Digest -and (Get-Sha $Destination) -eq $Digest) { return $Destination }
    if ($env:ARC7_OFFLINE -eq '1') { throw "Offline: missing or mismatched $Name. Extract the complete matching build beside the launcher." }
    $parent = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $part = $Destination + '.' + [Guid]::NewGuid().ToString('N') + '.part'
    try {
        Invoke-WebRequest -UseBasicParsing -Uri ($ReleaseBase + '/' + [Uri]::EscapeDataString($Name)) -OutFile $part -TimeoutSec 180
        if ($Digest -and (Get-Sha $part) -ne $Digest) { throw "SHA-256 mismatch for $Name. Nothing was replaced. Restart with a matching release bundle." }
        Move-Item -Force -LiteralPath $part -Destination $Destination
    } finally { if (Test-Path -LiteralPath $part) { Remove-Item -LiteralPath $part -Force } }
    return $Destination
}
function Read-Sums([string]$Path) {
    $result = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -notmatch '^([a-fA-F0-9]{64})  ([A-Za-z0-9][A-Za-z0-9._-]*)$') { throw 'Invalid release checksum manifest.' }
        $name = $Matches[2]
        if ($result.ContainsKey($name)) { throw "Duplicate checksum for $name" }
        $result[$name] = $Matches[1].ToLowerInvariant()
    }
    return $result
}
function Find-Local([string]$Name) {
    foreach ($base in @($ToolRoot, (Join-Path $ToolRoot '..\releases'), (Join-Path $ToolRoot 'releases'), (Join-Path $ToolRoot 'apps'))) {
        $candidate = Join-Path $base $Name
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return (Get-Item -LiteralPath $candidate).FullName }
    }
    return $null
}
function Get-Suite {
    if ($Apk) {
        if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK not found: $Apk" }
        return (Get-Item -LiteralPath $Apk).FullName
    }
    $local = Find-Local 'KoboArc7Freedom.apk'
    if ($local) { return $local }
    $directory = Join-Path $Cache 'downloads'
    $sumsPath = Get-ReleaseFile 'SHA256SUMS.txt' (Join-Path $directory 'SHA256SUMS.txt') ''
    $sums = Read-Sums $sumsPath
    if (-not $sums.ContainsKey('KoboArc7Freedom.apk')) { throw 'Release has no suite checksum.' }
    return Get-ReleaseFile 'KoboArc7Freedom.apk' (Join-Path $directory 'KoboArc7Freedom.apk') $sums['KoboArc7Freedom.apk']
}
function Expand-SafeZip([string]$Zip, [string]$Destination) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    $base = [IO.Path]::GetFullPath($Destination).TrimEnd('\') + '\'
    $archive = [IO.Compression.ZipFile]::OpenRead($Zip)
    try {
        foreach ($entry in $archive.Entries) {
            $target = [IO.Path]::GetFullPath((Join-Path $Destination $entry.FullName))
            if (-not $target.StartsWith($base, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe path in ZIP; extraction stopped.' }
            if ($entry.FullName.EndsWith('/') -or $entry.FullName.EndsWith('\')) { New-Item -ItemType Directory -Force -Path $target | Out-Null; continue }
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target,$false)
        }
    } finally { $archive.Dispose() }
}
function Resolve-Adb {
    if ($env:ARC7_ADB) {
        if (-not (Test-Path -LiteralPath $env:ARC7_ADB -PathType Leaf)) { throw 'ARC7_ADB does not point to a file.' }
        return $env:ARC7_ADB
    }
    foreach ($candidate in @((Join-Path $ToolRoot 'adb.exe'), (Join-Path $ToolRoot 'platform-tools\adb.exe'), (Join-Path $Cache 'adb\platform-tools\adb.exe'))) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    if ($env:ARC7_OFFLINE -eq '1') { throw 'adb.exe not found. Extract official Android platform-tools beside this launcher or put adb.exe in PATH.' }
    $directory = Join-Path $Cache 'adb'
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $zip = Join-Path $directory 'platform-tools.zip'
    Write-Host 'Downloading official Google platform-tools (one time)...'
    Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip -TimeoutSec 180
    $stage = Join-Path $directory ([Guid]::NewGuid().ToString('N'))
    try {
        Expand-SafeZip $zip $stage
        if (-not (Test-Path -LiteralPath (Join-Path $stage 'platform-tools\adb.exe'))) { throw 'The platform-tools ZIP did not contain adb.exe.' }
        Move-Item -LiteralPath (Join-Path $stage 'platform-tools') -Destination $directory
    } finally { Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue; Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue }
    return Join-Path $directory 'platform-tools\adb.exe'
}
function Invoke-Adb([string[]]$Arguments) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $text = (& $script:AdbPath @Arguments 2>&1 | Out-String); $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $old }
    if ($code -ne 0) { throw "ADB failed (exit $code): $text" }
    return $text
}
function Connect-Device {
    $script:AdbPath = Resolve-Adb
    $null = Invoke-Adb -Arguments @('start-server')
    $listing = Invoke-Adb -Arguments @('devices')
    $devices = @()
    foreach ($line in ($listing -split '\r?\n')) {
        if ($line -match '^(\S+)\s+(device|unauthorized|offline)(\s|$)') { $devices += [PSCustomObject]@{Id=$Matches[1]; State=$Matches[2]} }
    }
    if ($Serial) { $devices = @($devices | Where-Object { $_.Id -eq $Serial }) }
    if ($devices.Count -eq 0) { throw 'No selected ADB device. Enable USB debugging, connect a data-capable USB cable, install the USB driver if needed, and authorize this computer on the Kobo. Then retry.' }
    if ($devices.Count -ne 1) { throw 'Several ADB devices are connected. Run this launcher with -Serial YOUR_SERIAL to choose the Kobo; no device was changed.' }
    if ($devices[0].State -ne 'device') { throw ('Device is ' + $devices[0].State + '. Unlock the Kobo and accept its USB debugging authorization, then retry.') }
    $script:DeviceArgs = @('-s', $devices[0].Id)
    Write-Host ('Using ADB device ' + $devices[0].Id)
}
function Install-Suite {
    $suite = Get-Suite
    $response = Invoke-Adb ($script:DeviceArgs + @('install','-r',$suite))
    if ($response -notmatch '(?m)^Success\s*$' -or $response -match '(?m)^Failure\b') { throw "In-place update was not confirmed. Existing app/data retained. $response" }
    $started = Invoke-Adb ($script:DeviceArgs + @('shell','am','start','-n','ca.arc7freedom.app/.MainActivity'))
    if ($started -match '(?i)Error:|Exception|does not exist') { throw "Suite installed but could not open: $started" }
    Write-Host 'Suite installed in place. Review PATCHES / Apply or Retry All Patches and Android installation confirmations on the Kobo. No uninstall, wipe or automatic rooting is performed.'
}
function Start-Backup {
    $started = Invoke-Adb ($script:DeviceArgs + @('shell','am','startservice','-n','ca.arc7freedom.app/.Arc7AdbBackupService'))
    if ($started -match '(?i)Error:|Exception|not found|does not exist') { throw "Could not start the Kobo backup service: $started" }
    $forward = (Invoke-Adb ($script:DeviceArgs + @('forward','tcp:0','tcp:18777'))).Trim()
    if ($forward -notmatch '^\d{1,5}$' -or [int]$forward -lt 1 -or [int]$forward -gt 65535) { throw 'ADB did not allocate a backup tunnel. Install current platform-tools and retry.' }
    try {
        if (-not $Choice) {
            Write-Host '1: Essential backup  2: Full system clone (root required)  3: Personal files  4: Browser dashboard'
            $Choice = Read-Host 'Backup choice [1]'; if (-not $Choice) { $Choice = '1' }
        }
        if ($Choice -notin @('1','2','3','4')) { throw 'Choose 1, 2, 3 or 4.' }
        $url = 'http://127.0.0.1:' + $forward
        $ready = $false
        for ($attempt=0; $attempt -lt 15; $attempt++) {
            try {
                $ping = Invoke-WebRequest -UseBasicParsing -Uri ($url + '/ping') -TimeoutSec 2
                if ($ping.Content.Trim() -eq 'ARC7_ADB_BACKUP_READY') { $ready=$true; break }
            } catch { }
            Start-Sleep -Milliseconds 300
        }
        if (-not $ready) { throw 'The Kobo backup service did not become ready. Unlock the tablet, review installation/root prompts and retry. No backup was reported as complete.' }
        if ($Choice -eq '4') {
            if (-not $NoBrowser) { Start-Process ($url + '/') }
            Write-Host ($url + '/'); $null = Read-Host 'Keep this window open; press Enter after browser downloads finish'; return
        }
        $endpoints = @{'1'='/bundle.zip';'2'='/bundle.zip?full=1';'3'='/sdcard-files.zip'}
        $destination = if ($Output) { $Output } else { Join-Path ([Environment]::GetFolderPath('Desktop')) ('Kobo-Arc7-Backups\' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
        New-Item -ItemType Directory -Force -Path $destination | Out-Null
        $finalZip = Join-Path $destination ('Kobo-backup-' + [Guid]::NewGuid().ToString('N') + '.zip')
        $zip = $finalZip + '.part'
        Write-Host "Streaming directly to $zip; private backups are not uploaded anywhere."
        $request = [Net.HttpWebRequest]::Create($url + $endpoints[$Choice]); $request.Timeout=30000; $request.ReadWriteTimeout=180000
        $response = $request.GetResponse()
        try {
            $inputStream = $response.GetResponseStream(); $file = [IO.File]::Create($zip)
            try { $inputStream.CopyTo($file,65536); $file.Flush() } finally { $file.Dispose(); $inputStream.Dispose() }
        } finally { $response.Dispose() }
        $extract = Join-Path $destination ('extracted-' + [Guid]::NewGuid().ToString('N'))
        Expand-SafeZip $zip $extract
        $sums = Join-Path $extract 'SHA256SUMS.txt'
        if (Test-Path -LiteralPath $sums) {
            $checked = @{}
            foreach ($line in Get-Content -LiteralPath $sums) {
                if ($line -notmatch '^([a-fA-F0-9]{64})  (.+)$') { throw 'Invalid backup checksum entry.' }
                $digest=$Matches[1]; $relative=$Matches[2]
                if ($checked.ContainsKey($relative)) { throw 'Duplicate backup checksum entry.' }
                $checked[$relative]=$true
                $target=[IO.Path]::GetFullPath((Join-Path $extract $relative))
                if (-not $target.StartsWith(([IO.Path]::GetFullPath($extract).TrimEnd('\')+'\'),[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe backup checksum path.' }
                if ((Get-Sha $target) -ne $digest.ToLowerInvariant()) { throw "Backup checksum mismatch: $relative" }
            }
            Write-Host ("Backup transferred; " + $checked.Count + " SHA-256 entries verified: $destination. Inspect its device report; hashes do not prove backup completeness or restorability.")
        } else { Write-Host "ZIP downloaded/extracted: $destination. This endpoint supplied no SHA-256 manifest; no cryptographic verification is claimed." }
        Move-Item -LiteralPath $zip -Destination $finalZip
        if (-not $NoBrowser) { Invoke-Item -LiteralPath $destination }
    } finally {
        try { $null = Invoke-Adb ($script:DeviceArgs + @('forward','--remove',('tcp:'+$forward))) }
        catch { Write-Warning ('Backup tunnel could not be removed: ' + $_.Exception.Message) }
    }
}
function Start-StreamDeck {
    $scriptPath = Find-Local 'Start-PC-Stream-Deck.py'
    if (-not $scriptPath) {
        $sumsFile = Get-ReleaseFile 'SHA256SUMS.txt' (Join-Path $Cache 'downloads\SHA256SUMS.txt') ''
        $sums = Read-Sums $sumsFile
        if (-not $sums.ContainsKey('Start-PC-Stream-Deck.py')) { throw 'Missing companion checksum.' }
        $scriptPath = Get-ReleaseFile 'Start-PC-Stream-Deck.py' (Join-Path $Cache 'tools\Start-PC-Stream-Deck.py') $sums['Start-PC-Stream-Deck.py']
    }
    $candidates = @()
    if ($env:ARC7_PYTHON) { $candidates += ,@($env:ARC7_PYTHON) }
    else {
        foreach ($name in @('py.exe','python.exe','python3.exe')) {
            $command = Get-Command $name -ErrorAction SilentlyContinue
            if ($command) { if ($name -eq 'py.exe') { $candidates += ,@($command.Source,'-3') } else { $candidates += ,@($command.Source) } }
        }
    }
    foreach ($candidate in $candidates) {
        $exe=$candidate[0]; $prefix=@(); if ($candidate.Count -gt 1) { $prefix=@($candidate[1]) }
        $old=$ErrorActionPreference; $ErrorActionPreference='Continue'
        try { & $exe @prefix -c 'import sys;sys.exit(0 if sys.version_info >= (3,9) else 1)' 2>$null; $works=$LASTEXITCODE -eq 0 }
        catch { $works=$false } finally { $ErrorActionPreference=$old }
        if ($works) {
            # Resolve interpreter first. NEVER retry a failing application with a second Python.
            & $exe @prefix $scriptPath @RemainingArgs
            $result=$LASTEXITCODE
            if ($result -ne 0) { throw "Stream Deck exited with code $result. See its error above; it was not started a second time." }
            return
        }
    }
    throw 'Python 3.9+ was not found. Install Python from python.org with its launcher/PATH option, or set ARC7_PYTHON to your portable python.exe. Then rerun; no app was installed or started.'
}
function Start-Wifi {
    $directory = $Root
    if (-not $directory) {
        $suite=Find-Local 'KoboArc7Freedom.apk'
        if ($suite) { $directory=Split-Path -Parent $suite } else { $directory=Join-Path $Cache 'wifi' }
    }
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $directory=(Get-Item -LiteralPath $directory).FullName
    $sumsPath=Join-Path $directory 'SHA256SUMS.txt'
    if (-not (Test-Path -LiteralPath $sumsPath)) { $null=Get-ReleaseFile 'SHA256SUMS.txt' $sumsPath '' }
    $sums=Read-Sums $sumsPath
    # A small TCP server avoids HttpListener URL ACL/admin requirements. C# worker threads
    # have no PowerShell runspace dependency. Read-only, bounded headers, four clients, ranges.
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Collections.Generic;
using System.Threading;
using System.Security.Cryptography;

// Read-only and manifest-allowlisted. No directory listing, uploads, shell or URL ACL.
public static class Arc7ReadOnlyServer {
    static string root, origin;
    static Dictionary<string,string> hashes;
    static readonly Semaphore slots = new Semaphore(4,4);
    static TcpListener listener;
    static volatile bool stopping;
    public static volatile bool Running;
    public static string LastError = "";

    public static void Start(string directory,int port,string downloadBase,Dictionary<string,string> manifest) {
        root = Path.GetFullPath(directory); origin = downloadBase; hashes = manifest;
        listener = new TcpListener(IPAddress.Any,port);
        listener.Start(); // Synchronous: a busy port fails before the browser is opened.
        stopping = false; Running = true;
        var thread = new Thread(new ThreadStart(Accept)); thread.IsBackground = true; thread.Start();
    }
    public static void Stop() { stopping = true; if(listener != null) listener.Stop(); }
    static void Accept() {
        try {
            while(!stopping) {
                var client = listener.AcceptTcpClient();
                if(!slots.WaitOne(0)) { client.Close(); continue; }
                ThreadPool.QueueUserWorkItem(delegate(object value) {
                    var connection = (TcpClient)value;
                    try { Serve(connection); }
                    catch(Exception error) { Console.Error.WriteLine("Request failed: " + error.Message); }
                    finally { connection.Close(); slots.Release(); }
                },client);
            }
        } catch(Exception error) { if(!stopping) LastError = error.Message; }
        finally { Running = false; listener.Stop(); }
    }
    static void Header(Stream stream,int status,string type,long length,string extra) {
        string label = status==200 ? "OK" : status==206 ? "Partial Content" : status==416 ? "Range Not Satisfiable" :
            status==405 ? "Method Not Allowed" : status==404 ? "Not Found" : status==503 ? "Service Unavailable" : "Bad Request";
        byte[] bytes = Encoding.ASCII.GetBytes("HTTP/1.1 " + status + " " + label + "\r\nContent-Type: " + type +
            "\r\nContent-Length: " + length + "\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\n" + extra + "\r\n");
        stream.Write(bytes,0,bytes.Length);
    }
    static string Sha(string path) {
        using(var hash=SHA256.Create()) using(var input=File.OpenRead(path))
            return BitConverter.ToString(hash.ComputeHash(input)).Replace("-","").ToLowerInvariant();
    }
    sealed class DownloadClient : WebClient {
        protected override WebRequest GetWebRequest(Uri uri) {
            var request = base.GetWebRequest(uri); request.Timeout = 180000;
            var http = request as HttpWebRequest; if(http != null) http.ReadWriteTimeout = 180000;
            return request;
        }
    }
    static void Serve(TcpClient client) {
        client.ReceiveTimeout=10000; client.SendTimeout=120000;
        using(var stream=client.GetStream()) {
            var bytes=new List<byte>(); int next; bool complete=false;
            while(bytes.Count<16384 && (next=stream.ReadByte())>=0) {
                bytes.Add((byte)next); int n=bytes.Count;
                if(n>=4 && bytes[n-4]==13 && bytes[n-3]==10 && bytes[n-2]==13 && bytes[n-1]==10) { complete=true; break; }
            }
            string[] lines=Encoding.ASCII.GetString(bytes.ToArray()).Split(new string[]{"\r\n"},StringSplitOptions.None);
            string[] request=lines[0].Split(' ');
            if(!complete || request.Length!=3) { Header(stream,400,"text/plain",0,""); return; }
            bool head=request[0]=="HEAD";
            if(!head && request[0]!="GET") { Header(stream,405,"text/plain",0,"Allow: GET, HEAD\r\n"); return; }
            string url=Uri.UnescapeDataString(request[1].Split('?')[0]);
            if(url=="/" || url=="/index.html") {
                var html=new StringBuilder("<!doctype html><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Kobo Arc 7 Freedom - Wi-Fi installer</title><style>body{font:16px Segoe UI,sans-serif;background:#e9eff9;background:linear-gradient(135deg,#e7efff,#f8f8ff 50%,#e2f4ee);color:#18243a;padding:24px}main{max-width:760px;margin:auto;padding:28px;background:#ffffffb8;border:1px solid white;border-radius:32px;box-shadow:inset 0 1px 0 white,0 16px 60px #26487620}a{display:block;padding:16px;margin:10px 0;background:#ffffffb8;border:1px solid white;border-radius:24px;color:#1257b8;text-decoration:none}small{color:#4e607b}</style><main><h1>Kobo Arc 7 Freedom</h1><p>Read-only Wi-Fi installer. Keep this window open. Enable Unknown sources, then install APKs in place. No uninstall or data clearing.</p><small>Files not already local need Internet on this PC. All files are checked against SHA256SUMS.txt before serving. Use only a trusted private LAN.</small>");
                var names=new List<string>(hashes.Keys); names.Sort(StringComparer.OrdinalIgnoreCase);
                foreach(string entryName in names) {
                    if(entryName.EndsWith(".apk",StringComparison.OrdinalIgnoreCase) || entryName=="qr-transfer.html" || entryName=="Windows-Tools.zip")
                        html.Append("<a href='/"+Uri.EscapeDataString(entryName)+"'>"+System.Security.SecurityElement.Escape(entryName)+"</a>");
                }
                html.Append("</main>"); var data=Encoding.UTF8.GetBytes(html.ToString());
                Header(stream,200,"text/html; charset=utf-8",data.Length,""); if(!head) stream.Write(data,0,data.Length); return;
            }
            string name=url.TrimStart('/'); if(name.StartsWith("apps/",StringComparison.Ordinal)) name=name.Substring(5);
            string expected;
            if(name.Length==0 || name!=Path.GetFileName(name) || name.IndexOf('\\')>=0 || !hashes.TryGetValue(name,out expected)) {
                Header(stream,404,"text/plain",0,""); return;
            }
            string file=Path.Combine(root,name);
            if(!File.Exists(file)) { string nested=Path.Combine(Path.Combine(root,"apps"),name); if(File.Exists(nested)) file=nested; }
            // Serialize fills; never expose partial/unverified bytes. Existing mismatched local files
            // are kept intact if download or verification fails. ARC7_OFFLINE forbids all fetching.
            try {
                lock(hashes) {
                    if(!File.Exists(file) || Sha(file)!=expected) {
                        if(Environment.GetEnvironmentVariable("ARC7_OFFLINE")=="1") { Header(stream,404,"text/plain",0,""); return; }
                        string temp=Path.Combine(root,".arc7-"+Guid.NewGuid().ToString("N")+".part");
                        try {
                            using(var web=new DownloadClient()) web.DownloadFile(origin+"/"+Uri.EscapeDataString(name),temp);
                            if(Sha(temp)!=expected) throw new IOException("Release checksum mismatch; use a matching build.");
                            if(File.Exists(file)) File.Delete(file); File.Move(temp,file);
                        } finally { if(File.Exists(temp)) File.Delete(temp); }
                    }
                }
            } catch(Exception error) { Console.Error.WriteLine("Download refused: "+error.Message); Header(stream,503,"text/plain",0,""); return; }
            using(var input=File.OpenRead(file)) {
                long size=input.Length,start=0,end=size-1; bool range=false; string rangeLine=null;
                foreach(string line in lines) if(line.StartsWith("Range:",StringComparison.OrdinalIgnoreCase)) {
                    if(rangeLine!=null) { Header(stream,416,"text/plain",0,"Content-Range: bytes */"+size+"\r\n"); return; }
                    rangeLine=line.Substring(6).Trim();
                }
                if(rangeLine!=null) {
                    var match=System.Text.RegularExpressions.Regex.Match(rangeLine,@"^bytes=(\d*)-(\d*)$"); long a,b;
                    if(!match.Success || size==0 || (match.Groups[1].Value.Length==0 && match.Groups[2].Value.Length==0)) {
                        Header(stream,416,"text/plain",0,"Content-Range: bytes */"+size+"\r\n"); return;
                    }
                    if(match.Groups[1].Value.Length==0) {
                        if(!long.TryParse(match.Groups[2].Value,out a) || a<=0) { Header(stream,416,"text/plain",0,""); return; }
                        start=Math.Max(0,size-a);
                    } else {
                        if(!long.TryParse(match.Groups[1].Value,out a) || a>=size) { Header(stream,416,"text/plain",0,"Content-Range: bytes */"+size+"\r\n"); return; }
                        start=a;
                        if(match.Groups[2].Value.Length>0) {
                            if(!long.TryParse(match.Groups[2].Value,out b) || b<start) { Header(stream,416,"text/plain",0,""); return; }
                            end=Math.Min(end,b);
                        }
                    }
                    range=true;
                }
                string type=name.EndsWith(".apk",StringComparison.OrdinalIgnoreCase) ? "application/vnd.android.package-archive" :
                    name.EndsWith(".html") ? "text/html; charset=utf-8" : name.EndsWith(".zip") ? "application/zip" : "application/octet-stream";
                long length=Math.Max(0,end-start+1);
                Header(stream,range?206:200,type,length,"Accept-Ranges: bytes\r\n"+(range?"Content-Range: bytes "+start+"-"+end+"/"+size+"\r\n":""));
                if(head) return;
                input.Position=start; byte[] buffer=new byte[65536];
                while(length>0) { int n=input.Read(buffer,0,(int)Math.Min(length,buffer.Length)); if(n==0) throw new EndOfStreamException(); stream.Write(buffer,0,n); length-=n; }
            }
        }
    }
}
'@
    $manifest=New-Object 'System.Collections.Generic.Dictionary[string,string]' ([StringComparer]::Ordinal)
    foreach ($name in $sums.Keys) { $manifest.Add($name,$sums[$name]) }
    Write-Host "Read-only Wi-Fi installer: http://127.0.0.1:$Port/"
    foreach ($address in [Net.Dns]::GetHostAddresses([Net.Dns]::GetHostName())) { if ($address.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork) { Write-Host "Kobo browser: http://${address}:$Port/" } }
    Write-Host 'Allow the Windows Firewall prompt on PRIVATE networks only. No administrator URL reservation is needed. Ctrl+C stops the server.'
    [Arc7ReadOnlyServer]::Start($directory,$Port,$ReleaseBase,$manifest)
    try {
        if (-not $NoBrowser) { Start-Process "http://127.0.0.1:$Port/" }
        while ([Arc7ReadOnlyServer]::Running) { Start-Sleep -Milliseconds 400 }
        if ([Arc7ReadOnlyServer]::LastError) { throw [Arc7ReadOnlyServer]::LastError }
    } finally { [Arc7ReadOnlyServer]::Stop() }
}
try {
    switch ($Tool) {
        'wifi' { Start-Wifi }
        'install' { Connect-Device; Install-Suite }
        'backup' { Connect-Device; Install-Suite; Start-Backup }
        'streamdeck' { Start-StreamDeck }
    }
    exit 0
} catch {
    Write-Host ('ERROR: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
