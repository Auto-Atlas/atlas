@echo off
REM Launch the Jarvis sidecar on Windows-native Python.
REM Usage:  run.bat            (local, free)
REM         run.bat showtime   (cloud, premium voice)

if not "%~1"=="" set JARVIS_MODE=%~1

if not exist ".venv\Scripts\python.exe" (
  echo [!] No .venv found. Run setup first - see README.md
  exit /b 1
)

call .venv\Scripts\activate.bat
python bot.py
