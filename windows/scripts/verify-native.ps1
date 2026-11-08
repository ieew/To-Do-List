$ErrorActionPreference = 'Stop'

$exe = Join-Path $env:TEMP 'todo-target\debug\todo-windows.exe'
if (-not (Test-Path $exe)) {
  throw "Debug executable not found. Build with cargo in the start-desktop.bat environment first."
}

# 与 start-desktop.bat 保持一致：调试版数据固定到 windows\data。
$env:TODO_DATA_DIR = Join-Path (Split-Path $PSScriptRoot -Parent) 'data'
$env:TODO_VERIFY_WINDOWS = '1'
$process = Start-Process -FilePath $exe -PassThru -Wait
Write-Output ("exit=" + $process.ExitCode)

$report = Join-Path $env:TEMP 'todo-verify-windows.txt'
if (Test-Path $report) {
  Get-Content $report
} else {
  Write-Output 'NO_REPORT'
}
