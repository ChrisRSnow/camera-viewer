#!/usr/bin/env bash
# Builds a standalone Linux binary (dist/CameraViewer) - no Python
# installation needed to run it, just to build it. Run from this directory.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -d .venv ]; then
    python3 -m venv .venv
fi
.venv/bin/pip install --quiet --upgrade pip
.venv/bin/pip install --quiet -r requirements.txt pyinstaller

.venv/bin/pyinstaller --onefile --windowed --name CameraViewer main.py

echo
echo "Built: dist/CameraViewer"
