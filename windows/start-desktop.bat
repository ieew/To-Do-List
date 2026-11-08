@echo off
setlocal
cd /d "%~dp0"

if not exist node_modules (
  echo Installing npm dependencies...
  call npm install
  if errorlevel 1 goto :error
)

call :ensure_mingw
if errorlevel 1 goto :error

set "CARGO_TARGET_DIR=%TEMP%\todo-target"
set "CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER=x86_64-w64-mingw32-gcc.exe"

echo Starting Tauri desktop app...
call npm run tauri dev
exit /b 0

:ensure_mingw
where gcc >nul 2>nul && exit /b 0
set "WINLIBS=%LOCALAPPDATA%\Microsoft\WinGet\Packages\BrechtSanders.WinLibs.POSIX.UCRT_Microsoft.Winget.Source_8wekyb3d8bbwe\mingw64\bin"
if exist "%WINLIBS%\gcc.exe" (
  set "PATH=%WINLIBS%;%PATH%"
  exit /b 0
)
echo MinGW gcc not found. Install it with:
echo   winget install BrechtSanders.WinLibs.POSIX.UCRT
exit /b 1

:error
pause
exit /b 1
