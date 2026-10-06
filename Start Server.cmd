@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  echo Run Setup.cmd first.
  if not "%~1"=="--quiet" pause
  exit /b 1
)
".venv\Scripts\python.exe" desktop\control.py start
set "result=%errorlevel%"
if not "%~1"=="--quiet" pause
exit /b %result%
