# Start a throwaway FastAPI server and run the client sync end-to-end tests.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-sync.ps1

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot          # windows/
$repo = Split-Path -Parent $root                 # repository root
$cargoDir = Join-Path $root 'src-tauri'
$serverDir = Join-Path $repo 'server'
$python = Join-Path $serverDir '.venv\Scripts\python.exe'

if (-not (Test-Path $python)) {
    throw "Server virtualenv not found: $python"
}

$env:CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER = 'x86_64-w64-mingw32-gcc.exe'
$mingw = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\BrechtSanders.WinLibs.POSIX.UCRT_*\mingw64\bin" -Directory -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($mingw) {
    $env:PATH = "$($mingw.FullName);$env:PATH"
}

$port = 8011
$db = Join-Path $env:TEMP ("todo-sync-e2e-" + [guid]::NewGuid().ToString('N').Substring(0, 8) + ".sqlite3")
$env:TODO_SERVER_JWT_SECRET = 'e2e-secret-0123456789-abcdefghijklmnopqrstuvwxyz'
$env:TODO_SERVER_DB_PATH = $db
$env:TODO_SERVER_SNAPSHOT_EVERY_OPS = '0'
$env:TODO_SYNC_E2E_BASE_URL = "http://127.0.0.1:$port"

$server = Start-Process -FilePath $python `
    -ArgumentList @('-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', "$port", '--workers', '1') `
    -WorkingDirectory $serverDir -PassThru -WindowStyle Hidden

$pushed = $false
$inCargo = $false
try {
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            $response = Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$port/v1/health" -TimeoutSec 2
            if ($response.StatusCode -eq 200) {
                $pushed = $true
                break
            }
        } catch {
            # server still starting
        }
    }
    if (-not $pushed) {
        throw "Server did not become healthy on port $port"
    }

    Push-Location $cargoDir
    $inCargo = $true
    Write-Host '== unit tests (no desktop feature) =='
    & cargo test --lib --no-default-features
    if ($LASTEXITCODE -ne 0) {
        throw "cargo test --lib failed with exit code $LASTEXITCODE"
    }

    Write-Host '== end-to-end sync tests =='
    & cargo test --test sync_e2e --no-default-features -- --ignored --nocapture
    if ($LASTEXITCODE -ne 0) {
        throw "cargo test --test sync_e2e failed with exit code $LASTEXITCODE"
    }
    Write-Host 'sync verification passed'
} finally {
    if ($inCargo) {
        Pop-Location
    }
    if ($server -and -not $server.HasExited) {
        Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item "$db*" -Force -ErrorAction SilentlyContinue
}
