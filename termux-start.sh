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

# For global access, install the official ngrok binary if absent on a supported
# architecture. The auth token is always supplied by the user, never generated.
if ! command -v ngrok >/dev/null 2>&1 && command -v pkg >/dev/null 2>&1; then
  ARCH="$(uname -m)"
  case "$ARCH" in
    aarch64|arm64) NGROK_ARCH="arm64" ;;
    x86_64|amd64) NGROK_ARCH="amd64" ;;
    *) NGROK_ARCH="" ;;
  esac
  if [[ -n "$NGROK_ARCH" ]]; then
    if ! command -v curl >/dev/null 2>&1 || ! command -v tar >/dev/null 2>&1; then
      say "Installing curl/tar needed for the official ngrok download..."
      pkg install -y curl tar
    fi
    TMP_NGROK="$(mktemp -d)"
    if curl -fL --retry 2 --connect-timeout 15 "https://bin.ngrok.com/c/bNyj1mQVY4c/ngrok-v3-stable-linux-${NGROK_ARCH}.tgz" -o "$TMP_NGROK/ngrok.tgz" &&
       tar -xzf "$TMP_NGROK/ngrok.tgz" -C "$TMP_NGROK" &&
       [[ -f "$TMP_NGROK/ngrok" ]]; then
      install -m 755 "$TMP_NGROK/ngrok" "${PREFIX:-$HOME/.local}/bin/ngrok" 2>/dev/null || {
        mkdir -p "${PREFIX:-$HOME/.local}/bin"
        install -m 755 "$TMP_NGROK/ngrok" "${PREFIX:-$HOME/.local}/bin/ngrok"
      }
      export PATH="${PREFIX:-$HOME/.local}/bin:$PATH"
      if ! ngrok version >/dev/null 2>&1; then
        rm -f "${PREFIX:-$HOME/.local}/bin/ngrok"
        say "The official ngrok binary is incompatible with this device. Continuing with local mode."
      else
        say "Installed ngrok from the official HTTPS download."
      fi
    else
      say "Could not download ngrok. The local app can still run."
    fi
    rm -rf "$TMP_NGROK"
  else
    say "Automatic ngrok download is unsupported on architecture $ARCH. Local mode remains available."
  fi
fi

# Configure ngrok only with a token supplied privately by the user.
if command -v ngrok >/dev/null 2>&1; then
  NGROK_TOKEN="${NGROK_AUTHTOKEN:-}"
  if [[ -z "$NGROK_TOKEN" && -t 0 ]]; then
    printf '\n[UNISON] Enter your ngrok authtoken to configure public access (input hidden), or press Enter to skip: '
    IFS= read -r -s NGROK_TOKEN
    printf '\n'
  fi
  if [[ -n "$NGROK_TOKEN" ]]; then
    ngrok config add-authtoken "$NGROK_TOKEN" || say "ngrok token configuration failed; public tunnel may not start."
  fi
  unset NGROK_TOKEN
fi

say "Starting UNISON-P2P app + signaling on port $PORT."
say "The server stores no chat messages; only temporary signed connection signals are queued."
SERVER_LOG="$(mktemp)"
python server.py >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!
cleanup() {
  kill "$SERVER_PID" >/dev/null 2>&1 || true
  wait "$SERVER_PID" >/dev/null 2>&1 || true
  rm -f "$SERVER_LOG"
}
trap cleanup EXIT INT TERM

HEALTHY=0
for _ in $(seq 1 20); do
  if ! kill -0 "$SERVER_PID" >/dev/null 2>&1; then
    cat "$SERVER_LOG" >&2
    die "The Python server exited before becoming healthy."
  fi
  if python -c "import urllib.request; r=urllib.request.urlopen('http://127.0.0.1:$PORT/health', timeout=1); raise SystemExit(0 if r.status == 200 else 1)" >/dev/null 2>&1; then
    HEALTHY=1
    break
  fi
  sleep 1
done
[[ "$HEALTHY" == "1" ]] || { cat "$SERVER_LOG" >&2; die "Health check timed out; server was not exposed publicly."; }

say "Health check passed: http://127.0.0.1:$PORT/health"
if command -v ngrok >/dev/null 2>&1; then
  say "Starting public HTTPS/WSS tunnel. Share the HTTPS URL ngrok prints."
  if ngrok http "$PORT"; then
    :
  else
    say "ngrok did not start. The app will remain available locally; check your token/network and restart this script."
    wait "$SERVER_PID"
  fi
else
  say "No working ngrok agent is available. App is local only at http://127.0.0.1:$PORT/."
  say "For worldwide access, install a trusted public HTTPS/WSS tunnel or deploy the Python service."
  wait "$SERVER_PID"
fi
