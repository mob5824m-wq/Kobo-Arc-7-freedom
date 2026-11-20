@echo off
setlocal EnableExtensions DisableDelayedExpansion
title Kobo Arc 7 - In-place update and USB backup
set "ARC7_TOOL_ROOT=%~dp0"
set "ARC7_HELPER=%~dp0Arc7-Windows.ps1"
if not exist "%ARC7_HELPER%" set "ARC7_HELPER=%LOCALAPPDATA%\KoboArc7Freedom\tools\92f6f479f2dc1531\Arc7-Windows.ps1"
set "ARC7_POWERSHELL=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%ARC7_POWERSHELL%" set "ARC7_POWERSHELL=powershell.exe"
"%ARC7_POWERSHELL%" -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop';try{$p=$env:ARC7_HELPER;if(-not(Test-Path -LiteralPath $p)){if($env:ARC7_OFFLINE -eq '1'){throw 'Missing matching Arc7-Windows.ps1. Extract the complete Windows-Tools.zip.'};[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($p))|Out-Null;$t=$p+'.part';try{Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/mob5824m-wq/Kobo-Arc-7-freedom/releases/latest/download/Arc7-Windows.ps1' -OutFile $t -TimeoutSec 120;if((Get-FileHash -LiteralPath $t -Algorithm SHA256).Hash.ToLowerInvariant() -ne '92f6f479f2dc15311981ef7cb99aab49d45e3c377b4f143e13b3646095403479'){throw 'Helper checksum mismatch. Download the matching Windows-Tools.zip from the same build.'};Move-Item -LiteralPath $t -Destination $p -Force}finally{if(Test-Path -LiteralPath $t){Remove-Item -LiteralPath $t -Force}}};if((Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant() -ne '92f6f479f2dc15311981ef7cb99aab49d45e3c377b4f143e13b3646095403479'){throw 'Launcher/helper version mismatch. Extract the matching Windows-Tools.zip.'}}catch{Write-Host ('ERROR: '+$_.Exception.Message);exit 1}"
if errorlevel 1 goto failed
"%ARC7_POWERSHELL%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%ARC7_HELPER%" -Tool backup %*
set "ARC7_RESULT=%ERRORLEVEL%"
goto done
:failed
set "ARC7_RESULT=1"
:done
if not defined ARC7_NO_PAUSE pause
endlocal & exit /b %ARC7_RESULT%
