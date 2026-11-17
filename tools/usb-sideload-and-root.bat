@echo off
REM ============================================================================
REM Kobo Arc 7 Freedom — Windows USB ADB Sideload & Setup-Screen Rescue Helper
REM ============================================================================
setlocal
set "ROOT_DIR=%~dp0.."
set "MAIN_APK=%ROOT_DIR%\releases\KoboArc7Freedom.apk"

where adb >nul 2>nul
if errorlevel 1 (
    echo [ERROR] adb.exe not found in PATH.
    echo Copy releases\KoboArc7Freedom.apk to a microSD card and open it on the tablet,
    echo or extract Android SDK Platform-Tools into this folder and re-run.
    pause
    exit /b 1
)

if not exist "%MAIN_APK%" (
    echo [ERROR] Missing %MAIN_APK%
    pause
    exit /b 1
)

echo === Waiting for Kobo Arc 7 over USB ADB... ===
adb wait-for-device

echo === [1/2] Installing complete offline patch bundle in place ===
adb install -r "%MAIN_APK%"
if errorlevel 1 (
    echo [ERROR] APK installation failed.
    pause
    exit /b 1
)

echo === [2/2] Opening shared Install / Repair All Patches pipeline ===
adb shell am start -n ca.arc7freedom.app/.MainActivity --ez show_install_patches true
if errorlevel 1 exit /b 1

echo.
echo Suite installed. Check Install / Repair All Patches on the tablet.
echo   Rooted: bundled Home, stores, media and all 28 default apps update automatically.
echo   No root: use Install pending apps and confirm each APK in Android.
echo   Signing/storage/root restrictions stay pending; no automatic uninstall or wipe.
pause
