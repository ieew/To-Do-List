@echo off
setlocal
cd /d "%~dp0"

if not exist node_modules (
  echo [1/2] Installing npm dependencies...
  call npm install
  if errorlevel 1 goto :error
)

echo [2/2] Starting preview at http://127.0.0.1:1420
start "" cmd /c "ping -n 4 127.0.0.1 >nul & start http://127.0.0.1:1420"
call npm run dev
exit /b 0

:error
echo npm install failed.
pause
exit /b 1
