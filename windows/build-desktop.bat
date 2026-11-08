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

set "CARGO_TARGET_DIR=%TEMP%\todo-target"
set "CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER=x86_64-w64-mingw32-gcc.exe"

echo Building Tauri desktop app...
call npm run tauri:build
if errorlevel 1 goto :error

set "OUT=%CARGO_TARGET_DIR%\release"
if not exist "%OUT%\todo-windows.exe" (
  echo Build output not found: "%OUT%\todo-windows.exe"
  goto :error
)

if not exist desktop mkdir desktop
copy /y "%OUT%\todo-windows.exe" "desktop\TodoWindows.exe" >nul
if errorlevel 1 (
  echo Failed to copy executable. Close desktop\TodoWindows.exe if it is running, then retry.
  goto :error
)
if exist "%OUT%\WebView2Loader.dll" (
  copy /y "%OUT%\WebView2Loader.dll" "desktop\WebView2Loader.dll" >nul
)

echo.
echo Build finished.
echo   Build output: "%OUT%"
echo   Runnable:     desktop\TodoWindows.exe  ^(double-click run-built.bat^)
pause
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
echo.
echo Build failed. See the error messages above.
echo Common fixes:
echo   - Install the MinGW toolchain: winget install BrechtSanders.WinLibs.POSIX.UCRT
echo   - Delete "%TEMP%\todo-target" if the build cache is corrupted, then retry.
echo   - Close desktop\TodoWindows.exe before rebuilding.
pause
exit /b 1
