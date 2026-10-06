@echo off
setlocal
cd /d "%~dp0"
choice /m "Start Wardrobe automatically at Windows sign-in"
if errorlevel 2 exit /b 0
if not exist ".venv\Scripts\python.exe" exit /b 1
".venv\Scripts\python.exe" desktop\control.py enable-startup
pause
