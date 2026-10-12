#!/data/data/com.termux/files/usr/bin/bash
set -eu
cd "$(dirname "$0")"
if ! command -v python >/dev/null 2>&1; then
  echo "Python is missing. Install it first with: pkg install python"
  exit 1
fi
python -m pip install -r requirements.txt
echo
echo "UNISON-P2P Python app + signaling will listen on port 10000."
echo "In a second Termux session, expose this port with: ngrok http 10000"
echo "Open the HTTPS URL printed by ngrok on every device; peers enter only peer IDs."
exec python server.py
