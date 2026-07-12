# start-eve.ps1 — one-command launcher for the whole EVE/Jarvis stack.
#
# Multi-tenant by design: ALL behavior comes from env with sensible defaults — nothing
# user-specific is hardcoded here. Override any value by setting the env var before you
# run this (e.g.  $env:EVE_SPEAKER_THRESHOLD = "0.6"; .\start-eve.ps1 ).
#
# Identity (the owner name) is NOT here — it lives in life_dashboard.json (per-tenant).
#
# Usage:  pwsh -File .\start-eve.ps1        (or right-click > Run with PowerShell)

$ErrorActionPreference = "Continue"
$root = $PSScriptRoot
$py   = Join-Path $root ".venv\Scripts\python.exe"

# ---- shared config (defaults; override via real env) ------------------------
$env:PYTHONUTF8       = "1"
$env:PYTHONIOENCODING = "utf-8"
if (-not $env:EVE_SPEAKER_ID)        { $env:EVE_SPEAKER_ID        = "enabled" }  # real voice gating
if (-not $env:EVE_SPEAKER_THRESHOLD) { $env:EVE_SPEAKER_THRESHOLD = "0.55"    }  # forgiving: owner not denied
if (-not $env:EVE_OWNER_STICKY_S)    { $env:EVE_OWNER_STICKY_S    = "1800"    }  # owner latch (no mid-convo flip)
# Per-tenant identity/whys/goals live in a gitignored personal file (the committed
# life_dashboard.json is a neutral template). Point EVE at yours if it exists.
if (-not $env:EVE_LIFE_DASHBOARD) {
  $local = Join-Path $root "life_dashboard.local.json"
  if (Test-Path $local) { $env:EVE_LIFE_DASHBOARD = $local }
}
# Firebase Admin service-account key (server-side FCM wake sender). Kept OUTSIDE the
# repo. Per-tenant: each deployment points at its own key; never committed.
if (-not $env:EVE_FIREBASE_CREDENTIALS) {
  $fbkey = Join-Path $env:USERPROFILE "eve-firebase-admin.json"
  if (Test-Path $fbkey) { $env:EVE_FIREBASE_CREDENTIALS = $fbkey }
}

function Stop-Port($p) {
  $pids = (Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue).OwningProcess | Select-Object -Unique
  foreach ($procId in $pids) { try { Stop-Process -Id $procId -Force -ErrorAction Stop; Write-Host "  stopped pid $procId on :$p" } catch {} }
}
function Start-Svc($name, $argList, $extraEnv) {
  foreach ($k in $extraEnv.Keys) { Set-Item "Env:$k" $extraEnv[$k] }
  $out = Join-Path $root "$name.out.log"; $err = Join-Path $root "$name.err.log"
  Start-Process -FilePath $py -ArgumentList $argList -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err | Out-Null
  foreach ($k in $extraEnv.Keys) { Remove-Item "Env:$k" -ErrorAction SilentlyContinue }
  Write-Host "  started $name"
}
function Wait-Port($p, $secs) {
  for ($i=0; $i -lt $secs; $i++) { if (Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue) { return $true }; Start-Sleep 1 }
  return $false
}

Write-Host "Stopping any running services..."
8000,8765,8788,8799 | ForEach-Object { Stop-Port $_ }
Start-Sleep 2

Write-Host "Starting OpenJarvis brain (:8000)..."
Start-Svc "openjarvis" @("-m","openjarvis.cli","serve","--host","127.0.0.1","--port","8000") @{ PYTHONPATH = (Join-Path $root "app\src") }

Write-Host "Starting approval API (:8799)..."
Start-Svc "approval_api" @("approval_api.py") @{}

Write-Host "Starting desktop voice loop / bot.py (:8765)..."
Start-Svc "bot_desktop" @("bot.py") @{ JARVIS_HALF_DUPLEX = "1"; JARVIS_DAILY_BRIEFING = "0" }

Write-Host "Starting phone voice loop / phone_bot.py (:8788)..."
Start-Svc "phone_bot" @("phone_bot.py") @{ JARVIS_PHONE_HALF_DUPLEX = "1"; JARVIS_HALF_DUPLEX_TAIL_S = "2.2"; JARVIS_BRIEFING_FORCE = "1" }

Write-Host "Starting proactive wake scheduler (server-side morning ritual via FCM)..."
Start-Svc "push_scheduler" @("push_scheduler.py") @{}

Write-Host ""
Write-Host "Waiting for services to bind..."
$svc = @{ "OpenJarvis :8000" = 8000; "approval :8799" = 8799; "desktop voice :8765" = 8765; "phone voice :8788" = 8788 }
foreach ($k in $svc.Keys) { $ok = Wait-Port $svc[$k] 40; Write-Host ("  {0,-22} {1}" -f $k, $(if ($ok) {"UP"} else {"NOT UP (check $($svc[$k]).err.log)"})) }

Write-Host ""
Write-Host "Stack up. Desktop UI = the Tauri app (run separately):"
Write-Host "   cd app\frontend ; npm run tauri dev"
Write-Host "Config knobs (env): EVE_SPEAKER_ID, EVE_SPEAKER_THRESHOLD, EVE_OWNER_STICKY_S,"
Write-Host "   JARVIS_PHONE_HALF_DUPLEX, JARVIS_HALF_DUPLEX_TAIL_S, JARVIS_BRIEFING_FORCE."
