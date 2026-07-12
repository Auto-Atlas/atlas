# Boot the whole Jarvis stack. Idempotent: anything already running is left
# alone, so the autostart watchdog can call this every few minutes as a crash
# recovery net without side effects.
#
#   - Ollama            (port 11434) — LLM backend for both loops
#   - Syncthing         (port 8384)  — phone capture sync into ~\jarvis-inbox
#   - jarvis-sidecar    (port 8765)  — the voice loop (bot.py via run.bat)
#   - OpenJarvis server (port 8000)  — orchestrator agent + web UI (PWA)
#
# Usage:  jarvis-up           start everything, print status
#         jarvis-up -Open     also open the web UI in the browser

param([switch]$Open)

# Machine-specific paths come from env (set them in your PowerShell profile or
# a scheduled-task environment); the defaults assume the repo layout below your
# home directory. $UV falls back to uv on PATH.
$SIDECAR = if ($env:JARVIS_SIDECAR_DIR) { $env:JARVIS_SIDECAR_DIR } else { Join-Path $env:USERPROFILE 'atlas' }
$OPENJARVIS = if ($env:OPENJARVIS_DIR) { $env:OPENJARVIS_DIR } else { Join-Path $env:USERPROFILE 'OpenJarvis' }
$UV = if ($env:UV_EXE) { $env:UV_EXE } else { 'uv' }
$TASK_NAME = 'JarvisVoiceLoop'

function Test-Port([int]$Port) {
    $null -ne (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

# Append-forever crash logs are the flight recorder, but unbounded growth
# eventually hurts; roll to .old past 5 MB (one previous generation kept).
function Rotate-Log([string]$Path) {
    if ((Test-Path $Path) -and ((Get-Item $Path).Length -gt 5MB)) {
        Move-Item -Force $Path "$Path.old"
    }
}

# Re-enable the autostart watchdog (jarvis-down disables it so the stack
# stays down until you explicitly bring it back).
try { Enable-ScheduledTask -TaskName $TASK_NAME -ErrorAction Stop | Out-Null } catch {}

# --- Ollama ---------------------------------------------------------------
if (-not (Test-Port 11434)) {
    Write-Output '[jarvis-up] starting ollama...'
    # 8192-token window (default 4096 was one long conversation away from
    # silently truncating the system prompt). Inherited by the child process.
    $env:OLLAMA_CONTEXT_LENGTH = '8192'
    Start-Process -FilePath 'ollama' -ArgumentList 'serve' -WindowStyle Hidden
} else {
    Write-Output '[jarvis-up] ollama already up (11434)'
}

# --- Syncthing (phone -> jarvis-inbox capture sync) ------------------------
# Optional: phone capture sync. Set SYNCTHING_EXE, or let PATH resolve it; if
# absent the stack still boots (sync is a nice-to-have, and Test-Path says so).
$SYNCTHING = if ($env:SYNCTHING_EXE) { $env:SYNCTHING_EXE } else { (Get-Command syncthing -ErrorAction SilentlyContinue).Source }
if (-not (Test-Port 8384)) {
    if ($SYNCTHING -and (Test-Path $SYNCTHING)) {
        Write-Output '[jarvis-up] starting syncthing...'
        Start-Process -FilePath $SYNCTHING -ArgumentList '--no-browser', '--no-console' -WindowStyle Hidden
    } else {
        Write-Output '[jarvis-up] syncthing not found (skipping inbox sync)'
    }
} else {
    Write-Output '[jarvis-up] syncthing already up (8384)'
}

# --- Voice sidecar ---------------------------------------------------------
if (-not (Test-Port 8765)) {
    Write-Output '[jarvis-up] starting voice sidecar...'
    # APPEND (2>>) with a banner per boot — the old overwrite (2>) destroyed
    # crash history on every launch, which is why a day of ~16 silent restarts
    # left no trace. The log survives; restarts are countable after the fact.
    Rotate-Log "$SIDECAR\sidecar.err.log"
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    [IO.File]::AppendAllText("$SIDECAR\sidecar.err.log", "`r`n===== sidecar boot $stamp =====`r`n")
    Start-Process -FilePath cmd.exe -WorkingDirectory $SIDECAR `
        -ArgumentList '/c', "$SIDECAR\run.bat 2>> $SIDECAR\sidecar.err.log" `
        -WindowStyle Hidden
} else {
    Write-Output '[jarvis-up] voice sidecar already up (8765)'
}

# --- Phone voice loop (WebRTC over the tailnet, port 8788) -----------------
if (-not (Test-Port 8788)) {
    Write-Output '[jarvis-up] starting phone voice server...'
    Rotate-Log "$SIDECAR\phone_bot.err.log"
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    [IO.File]::AppendAllText("$SIDECAR\phone_bot.err.log", "`r`n===== phone boot $stamp =====`r`n")
    Start-Process -FilePath cmd.exe -WorkingDirectory $SIDECAR `
        -ArgumentList '/c', "$SIDECAR\.venv\Scripts\python.exe $SIDECAR\phone_bot.py 2>> $SIDECAR\phone_bot.err.log" `
        -WindowStyle Hidden
} else {
    Write-Output '[jarvis-up] phone voice server already up (8788)'
}

# --- OpenJarvis server (agent brain tier 3 + web UI) -----------------------
if (-not (Test-Port 8000)) {
    Write-Output '[jarvis-up] starting openjarvis server...'
    Start-Process -FilePath $UV -WorkingDirectory $OPENJARVIS `
        -ArgumentList 'run', 'jarvis', 'serve', '--port', '8000',
            '--engine', 'ollama', '--model', 'llama3.1:8b', '--agent', 'orchestrator' `
        -WindowStyle Hidden `
        -RedirectStandardError "$OPENJARVIS\openjarvis.serve.err.log" `
        -RedirectStandardOutput "$OPENJARVIS\openjarvis.serve.out.log"
} else {
    Write-Output '[jarvis-up] openjarvis server already up (8000)'
}

# --- Wait briefly, then report real state ----------------------------------
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
    if ((Test-Port 8765) -and (Test-Port 8000) -and (Test-Port 11434)) { break }
    Start-Sleep -Seconds 3
}

Write-Output ''
Write-Output ("  ollama    (11434): " + $(if (Test-Port 11434) { 'UP' } else { 'DOWN' }))
Write-Output ("  syncthing (8384):  " + $(if (Test-Port 8384) { 'UP' } else { 'DOWN' }))
Write-Output ("  sidecar   (8765):  " + $(if (Test-Port 8765) { 'UP' } else { 'DOWN - check sidecar.err.log' }))
Write-Output ("  webhook   (8787):  " + $(if (Test-Port 8787) { 'UP' } else { 'DOWN - check sidecar.err.log' }))
Write-Output ("  phone     (8788):  " + $(if (Test-Port 8788) { 'UP' } else { 'DOWN - check phone_bot.err.log' }))
Write-Output ("  server    (8000):  " + $(if (Test-Port 8000) { 'UP' } else { 'DOWN - check openjarvis.serve.err.log' }))

if ($Open) { Start-Process 'http://localhost:8000' }
