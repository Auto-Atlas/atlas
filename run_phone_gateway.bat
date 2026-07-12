@echo off
REM One-origin gateway for the phone JARVIS page: serves app\frontend\dist-phone
REM and proxies WebRTC signaling (-> :8788) and the metrics bridge (-> :8766) so
REM the phone reaches everything from one Tailscale HTTPS origin.
REM
REM Tailscale exposes it (no admin) with:
REM   tailscale serve --bg --https=8445 http://127.0.0.1:8795
REM Then open https://<your-tailnet-host>:8445 on the phone
REM (find your host with `tailscale status` / the admin console — never commit the literal).
REM
REM Rebuild the page after editing app\frontend:  cd app\frontend ^&^& npm run build:phone
REM Add this .bat to Startup (shell:startup) to keep it up across reboots.

cd /d "%~dp0"
if exist ".venv\Scripts\python.exe" (
  ".venv\Scripts\python.exe" phone_gateway.py
) else (
  python phone_gateway.py
)
