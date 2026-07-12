# Shut the Jarvis stack down and KEEP it down: disables the autostart
# watchdog first so nothing resurrects until you run jarvis-up again.
#
# Ollama is left running on purpose — other things use it. Add -Ollama to
# stop it too.
#
# Usage:  jarvis-down            stop sidecar + openjarvis server
#         jarvis-down -Ollama    also stop ollama

param([switch]$Ollama)

$TASK_NAME = 'JarvisVoiceLoop'

try {
    Disable-ScheduledTask -TaskName $TASK_NAME -ErrorAction Stop | Out-Null
    Write-Output "[jarvis-down] autostart watchdog disabled"
} catch {
    Write-Output "[jarvis-down] no autostart task to disable"
}

function Stop-ByPort([int]$Port, [string]$What) {
    $conns = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if (-not $conns) {
        Write-Output "[jarvis-down] $What already down ($Port)"
        return
    }
    foreach ($owningPid in ($conns.OwningProcess | Sort-Object -Unique)) {
        # Kill the whole tree: uv/cmd wrappers spawn the real server as a child.
        & taskkill /PID $owningPid /T /F 2>$null | Out-Null
    }
    Write-Output "[jarvis-down] stopped $What ($Port)"
}

Stop-ByPort 8765 'voice sidecar'
Stop-ByPort 8000 'openjarvis server'

# Any orphaned bot.py without a listener (crashed mid-boot) dies too. run.bat
# starts it as plain "python bot.py", so the sidecar path shows up in the
# interpreter's ExecutablePath (.venv), not the command line.
Get-CimInstance Win32_Process -Filter "Name like 'python%'" -ErrorAction SilentlyContinue |
    Where-Object {
        $_.CommandLine -match 'bot\.py' -and
        ($_.ExecutablePath -match 'jarvis-sidecar' -or $_.CommandLine -match 'jarvis-sidecar')
    } |
    ForEach-Object { & taskkill /PID $_.ProcessId /T /F 2>$null | Out-Null }

if ($Ollama) { Stop-ByPort 11434 'ollama' }

Write-Output "[jarvis-down] done - run jarvis-up to bring it back"
