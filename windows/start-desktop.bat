@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

if not exist node_modules (
  echo Installing npm dependencies...
  call npm install
  if errorlevel 1 goto :error
)

call :ensure_mingw
if errorlevel 1 goto :error

rem Tauri 的 devUrl 固定为 1420，端口被占用时 Vite 会直接退出。
netstat -ano | findstr /c:":1420" | findstr /i "LISTENING" >nul
if not errorlevel 1 (
  echo Port 1420 is already in use.
  echo Close the preview server ^(npm run dev^) before starting the desktop app.
  goto :error
)

set "CARGO_TARGET_DIR=%TEMP%\todo-target"
set "CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER=x86_64-w64-mingw32-gcc.exe"
rem 开发模式把数据固定到 windows\data，避免落在临时构建目录里被清理。
set "TODO_DATA_DIR=%~dp0data"

echo Starting Tauri desktop app...
call npm run tauri:dev
if errorlevel 1 goto :error
exit /b 0

:ensure_mingw
rem 优先使用 WinLibs：即使 PATH 里已有残缺的 gcc，也能保证 dlltool 可用。
set "WINLIBS=%LOCALAPPDATA%\Microsoft\WinGet\Packages\BrechtSanders.WinLibs.POSIX.UCRT_Microsoft.Winget.Source_8wekyb3d8bbwe\mingw64\bin"
if exist "%WINLIBS%\dlltool.exe" (
  set "PATH=%WINLIBS%;%PATH%"
  exit /b 0
)
where dlltool >nul 2>nul
if not errorlevel 1 exit /b 0
echo MinGW dlltool not found. Install it with:
echo   winget install BrechtSanders.WinLibs.POSIX.UCRT
exit /b 1

:error
pause
exit /b 1
