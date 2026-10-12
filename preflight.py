#!/usr/bin/env python3
"""Pre-start checks for UNISON-P2P. Does not create or modify user data."""
import argparse
import importlib.metadata
import os
import re
import socket
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REQUIRED_FILES = ("server.py", "index.html", "requirements.txt", "manifest.json", "sw.js")
BOUNDS = {"aiohttp": ((3, 9), (4, 0)), "cryptography": ((42, 0), (47, 0))}


def version_tuple(value):
    match = re.match(r"^(\d+)\.(\d+)(?:\.(\d+))?", value)
    if not match:
        raise ValueError(f"Unrecognized package version: {value}")
    return tuple(int(part or 0) for part in match.groups())


def check_dependencies():
    failures = []
    for package, (minimum, maximum) in BOUNDS.items():
        try:
            installed = importlib.metadata.version(package)
            parsed = version_tuple(installed)
            if parsed < minimum or parsed >= maximum:
                failures.append(f"{package} {installed} is outside supported range {minimum} <= version < {maximum}")
            else:
                print(f"[OK] {package} {installed}")
        except importlib.metadata.PackageNotFoundError:
            failures.append(f"{package} is not installed")
        except ValueError as exc:
            failures.append(str(exc))
    return failures


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tests", action="store_true", help="compile Python and run unit tests")
    parser.add_argument("--check-port", action="store_true", help="ensure PORT is currently available")
    args = parser.parse_args()

    errors = []
    for filename in REQUIRED_FILES:
        if (ROOT / filename).is_file():
            print(f"[OK] required file: {filename}")
        else:
            errors.append(f"Required file is missing: {filename}")

    errors.extend(check_dependencies())

    if args.check_port:
        port = int(os.environ.get("PORT", "10000"))
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
                sock.bind(("0.0.0.0", port))
            print(f"[OK] TCP port {port} is available")
        except OSError:
            errors.append(f"TCP port {port} is already in use. Stop the old server or choose another PORT.")

    if errors:
        print("\nPreflight failed:", file=sys.stderr)
        for error in errors:
            print(f" - {error}", file=sys.stderr)
        return 1

    if args.tests:
        print("\n[CHECK] Compile Python files")
        result = subprocess.run(
            [sys.executable, "-m", "compileall", "-q", "server.py", "preflight.py", "tests"],
            cwd=ROOT,
        )
        if result.returncode:
            return result.returncode
        print("[CHECK] Run security tests")
        result = subprocess.run(
            [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"],
            cwd=ROOT,
        )
        if result.returncode:
            return result.returncode

    print("\nUNISON-P2P preflight passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
