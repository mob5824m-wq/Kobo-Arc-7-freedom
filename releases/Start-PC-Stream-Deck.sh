#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
echo "===================================================================="
echo "  Kobo Arc 7 Freedom — PC Stream Deck Host + OBS / Discord Controller"
echo "  Listening on Wi-Fi LAN + USB ADB (:18991)"
echo "  Web Designer Studio: http://127.0.0.1:18995"
echo "===================================================================="
exec python3 Start-PC-Stream-Deck.py "$@"
