# Atlas one-line installer (Windows).
#   powershell -ExecutionPolicy Bypass -c "irm https://raw.githubusercontent.com/Auto-Atlas/atlas/main/install.ps1 | iex"
# Idempotent: re-running updates the checkout and dependencies. Overrides:
#   ATLAS_DIR   where to install (default: %USERPROFILE%\atlas)
#   ATLAS_REPO  git URL (default: the Auto-Atlas/atlas repo)
$ErrorActionPreference = 'Stop'

function Fail($msg) { Write-Host "`nATLAS INSTALL FAILED: $msg" -ForegroundColor Red; exit 1 }
function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

$repo = if ($env:ATLAS_REPO) { $env:ATLAS_REPO } else { 'https://github.com/Auto-Atlas/atlas.git' }
$dir  = if ($env:ATLAS_DIR)  { $env:ATLAS_DIR }  else { Join-Path $env:USERPROFILE 'atlas' }

# ---- prerequisites: fail loudly with the exact fix, never limp on ----------
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Fail "git is not installed. Get it from https://git-scm.com/download/win (defaults are fine), then re-run."
}
$py = $null
foreach ($cand in @('py -3.11', 'python3.11', 'python')) {
    try {
        $v = Invoke-Expression "$cand --version" 2>$null
        if ($v -match 'Python 3\.11\.') { $py = $cand; break }
    } catch {}
}
if (-not $py) {
    Fail ("Python 3.11 is required (3.12+/3.10- are not the tested stack). Install from " +
          "https://www.python.org/downloads/release/python-3119/ - CHECK 'Add python.exe to PATH' - then re-run.")
}

# ---- get the code -----------------------------------------------------------
if (Test-Path (Join-Path $dir '.git')) {
    Step "Updating existing checkout at $dir"
    git -C $dir pull --ff-only; if ($LASTEXITCODE -ne 0) { Fail "git pull failed in $dir (local changes?). Resolve and re-run." }
} else {
    Step "Cloning $repo -> $dir"
    git clone $repo $dir; if ($LASTEXITCODE -ne 0) { Fail "git clone failed." }
}

# ---- python env -------------------------------------------------------------
$venvPy = Join-Path $dir '.venv\Scripts\python.exe'
if (-not (Test-Path $venvPy)) {
    Step "Creating virtualenv (.venv)"
    Invoke-Expression "$py -m venv `"$dir\.venv`""; if (-not (Test-Path $venvPy)) { Fail "venv creation failed." }
}
Step "Installing dependencies (this downloads the AI stack - several GB on first run)"
& $venvPy -m pip install --upgrade pip --quiet
& $venvPy -m pip install -r (Join-Path $dir 'requirements.txt')
if ($LASTEXITCODE -ne 0) { Fail "pip install failed - scroll up for the real error." }

# ---- config -----------------------------------------------------------------
$envFile = Join-Path $dir '.env'
if (-not (Test-Path $envFile)) {
    Step "Creating .env from .env.example"
    Copy-Item (Join-Path $dir '.env.example') $envFile
}
if (-not (Get-Command nvidia-smi -ErrorAction SilentlyContinue)) {
    Step "No NVIDIA GPU detected - switching Whisper to CPU mode in .env"
    $envText = [IO.File]::ReadAllText($envFile)
    $envText = $envText -replace 'WHISPER_DEVICE=cuda', 'WHISPER_DEVICE=cpu' -replace 'WHISPER_COMPUTE=float16', 'WHISPER_COMPUTE=int8'
    [IO.File]::WriteAllText($envFile, $envText)
}

# ---- ollama (the local brain) ----------------------------------------------
if (-not (Get-Command ollama -ErrorAction SilentlyContinue)) {
    Fail ("Ollama is required for local mode. Install it from https://ollama.com/download/windows, " +
          "then re-run this installer (it will pull the model and finish).")
}
Step "Pulling the voice model (qwen3:8b - no-op if you already have it)"
ollama pull qwen3:8b; if ($LASTEXITCODE -ne 0) { Fail "ollama pull failed - is the Ollama service running?" }

Write-Host ""
Write-Host "Atlas is installed." -ForegroundColor Green
Write-Host "  Start it:   cd `"$dir`"; .\run.bat"
Write-Host "  It greets you out loud (~20s to load), then just talk."
Write-Host "  Full guide: $dir\SETUP-GUIDE.md (phone/SMS extras, troubleshooting)"
