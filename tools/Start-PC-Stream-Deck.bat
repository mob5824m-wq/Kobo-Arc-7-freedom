@echo off
title Kobo Arc 7 Freedom — PC Stream Deck Server (:18991)
cd /d "%~dp0"
echo ====================================================================
echo   Kobo Arc 7 Freedom — PC Stream Deck Host + OBS / Discord Controller
echo   Wi-Fi server :18991 | USB ADB host :18992 -> Kobo :18991
echo   Web Designer Studio: http://127.0.0.1:18995
echo ====================================================================
python Start-PC-Stream-Deck.py || py -3 Start-PC-Stream-Deck.py
pause
