$ErrorActionPreference = 'Stop'

$exe = Join-Path $env:TEMP 'todo-target\debug\todo-windows.exe'
if (-not (Test-Path $exe)) {
  throw "Debug executable not found. Build with cargo in the start-desktop.bat environment first."
}

$env:TODO_VERIFY_WINDOWS = '1'
$process = Start-Process -FilePath $exe -PassThru -Wait
Write-Output ("exit=" + $process.ExitCode)

$report = Join-Path $env:TEMP 'todo-verify-windows.txt'
if (Test-Path $report) {
  Get-Content $report
} else {
  Write-Output 'NO_REPORT'
}
