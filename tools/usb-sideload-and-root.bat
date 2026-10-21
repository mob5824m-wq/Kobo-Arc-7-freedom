@echo off
REM ============================================================================
REM Kobo Arc 7 Freedom — Windows USB ADB Sideload & Setup-Screen Rescue Helper
REM ============================================================================
setlocal
set "ROOT_DIR=%~dp0.."
set "MAIN_APK=%ROOT_DIR%\releases\KoboArc7Freedom.apk"
set "HOME_APK=%ROOT_DIR%\releases\VanillaAndroid4Home.apk"

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

echo === [1/3] Installing KoboArc7Freedom.apk ===
adb install -r "%MAIN_APK%"
if errorlevel 1 (
    echo [ERROR] APK installation failed.
    pause
    exit /b 1
)

if exist "%HOME_APK%" (
    echo === [2/3] Installing VanillaAndroid4Home.apk ===
    adb install -r "%HOME_APK%"
)

echo === [3/3] Launching Kobo Arc 7 Freedom directly over Kobo UI ===
adb shell am start -n ca.arc7freedom.app/.MainActivity

echo.
echo SUCCESS! Look at your Kobo Arc 7 tablet screen now:
echo   1. Tap "ROOT TABLET NOW" in Tab 1
echo   2. Back up your stock Kobo OS to microSD in Tab 2
echo   3. Convert to Clean Stock Android 4.2 in Tab 3!
pause
