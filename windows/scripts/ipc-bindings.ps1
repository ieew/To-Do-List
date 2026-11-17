# 生成 / 校验前端 IPC bindings（tauri-specta）。
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ipc-bindings.ps1           # 覆盖写入 src/lib/ipc-bindings.generated.ts
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ipc-bindings.ps1 -Check    # 只校验是否与 Rust 端一致，不改文件

param([switch]$Check)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot          # windows/
$cargoDir = Join-Path $root 'src-tauri'
$target = Join-Path $root 'src\lib\ipc-bindings.generated.ts'

# 与 start-desktop.bat 一致：中文路径必须换 target 目录 + WinLibs 链接器。
$env:CARGO_TARGET_DIR = Join-Path $env:TEMP 'todo-target'
$env:CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER = 'x86_64-w64-mingw32-gcc.exe'
$mingw = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\BrechtSanders.WinLibs.POSIX.UCRT_*\mingw64\bin" -Directory -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($mingw) {
    $env:PATH = "$($mingw.FullName);$env:PATH"
}

$manifest = Join-Path $cargoDir 'Cargo.toml'

if (-not $Check) {
    Remove-Item Env:\IPC_BINDINGS_OUT -ErrorAction SilentlyContinue
    & cargo run --quiet --manifest-path $manifest --features bindings-export --bin export_bindings
    if ($LASTEXITCODE -ne 0) { throw 'bindings 生成失败' }
    Write-Output ("BINDINGS_WRITTEN " + $target)
    exit 0
}

$out = Join-Path $env:TEMP 'todo-ipc-bindings-check.ts'
if (Test-Path $out) { Remove-Item $out -Force }
$env:IPC_BINDINGS_OUT = $out
& cargo run --quiet --manifest-path $manifest --features bindings-export --bin export_bindings
if ($LASTEXITCODE -ne 0) { throw 'bindings 生成失败' }
if (-not (Test-Path $out)) { throw "没有生成 $out" }

$fresh = Get-Content $out -Raw
$committed = if (Test-Path $target) { Get-Content $target -Raw } else { '' }

if ($fresh -ne $committed) {
    Write-Output 'BINDINGS_STALE'
    $diff = Compare-Object ($committed -split "`r?`n") ($fresh -split "`r?`n") |
        Select-Object -First 20
    foreach ($line in $diff) {
        Write-Output ("{0} {1}" -f $line.SideIndicator, $line.InputObject)
    }
    Write-Output '修复：npm run gen:bindings 后提交 src/lib/ipc-bindings.generated.ts'
    exit 1
}

Write-Output 'BINDINGS_OK'
