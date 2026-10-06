@echo off
setlocal
cd /d "%~dp0"
py -3.11 -c "import sys; assert sys.version_info[:2] == (3,11)" >nul 2>&1
if errorlevel 1 (
  echo Install Python 3.11 for your Windows user from https://www.python.org/downloads/windows/
  echo Include the Python launcher, then run Setup.cmd again.
  pause
  exit /b 1
)
if not exist ".venv\Scripts\python.exe" py -3.11 -m venv .venv
if errorlevel 1 exit /b 1
".venv\Scripts\python.exe" -m pip install --disable-pip-version-check -r server\requirements.txt
if errorlevel 1 (
  echo Installation failed. Check your internet connection and try again.
  pause
  exit /b 1
)
".venv\Scripts\python.exe" -m pip check
if errorlevel 1 exit /b 1
".venv\Scripts\python.exe" -c "import onnxruntime as o; o.InferenceSession('models/u2netp.onnx',providers=['CPUExecutionProvider']); print('Cutout model loaded.')"
if errorlevel 1 exit /b 1
echo Setup complete. Open Dashboard.cmd starts the server and opens its private dashboard.
echo Optional startup is OFF. Read README.md before connecting Tailscale or AI.
pause
