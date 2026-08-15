@echo off
REM Builds a standalone Windows executable (dist\CameraViewer.exe) - no
REM Python installation needed to run it, just to build it. Run this from
REM this directory, on Windows, in a Command Prompt or PowerShell.
REM Not tested by the developer (built/verified on Linux only, this script
REM mirrors build_linux.sh) - if --windowed causes a Qt plugin loading
REM issue in the frozen build, drop it (leaves a console window visible,
REM useful for seeing the actual error) and re-run.

if not exist .venv (
    python -m venv .venv
)
.venv\Scripts\pip install --quiet --upgrade pip
.venv\Scripts\pip install --quiet -r requirements.txt pyinstaller

.venv\Scripts\pyinstaller --onefile --windowed --name CameraViewer main.py

echo.
echo Built: dist\CameraViewer.exe
