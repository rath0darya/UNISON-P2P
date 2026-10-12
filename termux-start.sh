#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

say() { printf '\n[UNISON] %s\n' "$*"; }
die() { printf '\n[UNISON ERROR] %s\n' "$*" >&2; exit 1; }

# Python is the only required runtime. Install it automatically in Termux if missing.
if ! command -v python >/dev/null 2>&1; then
  command -v pkg >/dev/null 2>&1 || die "Python is missing and this is not a Termux environment. Install Python 3, then rerun this script."
  say "Python is missing. Installing it from the configured Termux package repository..."
  pkg update -y
  pkg install -y python
fi

command -v python >/dev/null 2>&1 || die "Python installation did not complete."
python -m pip --version >/dev/null 2>&1 || {
  if command -v pkg >/dev/null 2>&1; then
    say "Python pip is missing. Installing it..."
    pkg install -y python-pip || true
  fi
}
python -m pip --version >/dev/null 2>&1 || die "pip is unavailable. Fix the Termux Python installation and rerun."

for file in server.py index.html requirements.txt manifest.json sw.js preflight.py tests/test_registration.py; do
  [[ -f "$file" ]] || die "Required project file is missing: $file. Run git pull --ff-only and try again."
done

# Avoid downloading packages when the installed versions already satisfy the app.
if ! python -c 'import aiohttp, cryptography; from importlib.metadata import version; assert (3,9) <= tuple(map(int, version("aiohttp").split(".")[:2])) < (4,0); assert (42,0) <= tuple(map(int, version("cryptography").split(".")[:2])) < (47,0)' >/dev/null 2>&1; then
  say "Required Python packages are missing or outside supported versions. Installing from requirements.txt..."
  if ! python -m pip install -r requirements.txt; then
    if command -v pkg >/dev/null 2>&1; then
      say "pip installation failed. Trying Termux's signed package repository as a fallback..."
      pkg update -y
      pkg install -y python-aiohttp python-cryptography
    else
      die "Could not install Python dependencies. Check internet access and package errors above."
    fi
  fi
fi

python -c 'import aiohttp, cryptography' >/dev/null 2>&1 || die "aiohttp/cryptography are still unavailable after installation."
say "Checking files, package versions, port availability, Python syntax, and security tests..."
python preflight.py --tests --check-port || die "Preflight failed. The server was NOT started; fix the error above and rerun."

PORT="${PORT:-10000}"
export PORT

say "Starting UNISON-P2P app + signaling on port $PORT."
say "Local health check: http://127.0.0.1:$PORT/health"
if command -v ngrok >/dev/null 2>&1; then
  say "ngrok is installed. In a second Termux session, run: ngrok http $PORT"
  say "If ngrok is not authenticated, configure its token with: ngrok config add-authtoken YOUR_TOKEN"
else
  say "ngrok is not installed. Local operation will work, but worldwide access needs a public HTTPS/WSS tunnel or hosted deployment."
  say "Install ngrok from its official download page: https://ngrok.com/download/linux"
fi

exec python server.py
