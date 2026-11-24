# Build dsh-kit.dex (Windows / PowerShell, equivalent to tools/build.sh)
# Usage: powershell -ExecutionPolicy Bypass -File tools\build.ps1
$ErrorActionPreference = "Stop"

$base = Split-Path $PSScriptRoot -Parent
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$ajar = if ($env:AJAR) { $env:AJAR } else { Get-ChildItem "$sdk\platforms\*\android.jar" | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName }
$d8 = if ($env:R8) { $env:R8 } else { "$sdk\build-tools\36.0.0\lib\d8.jar" }
$paho = if ($env:PAHO) { $env:PAHO } else { "$base\tools\lib\org.eclipse.paho.client.mqttv3-1.2.5.jar" }
$out = Join-Path $base "build"
$classes = Join-Path $out "classes"
$dexDir = Join-Path $out "dex"
$dest = Join-Path $base "out"

foreach ($path in @($ajar, $d8, $paho)) {
    if (-not (Test-Path $path)) { throw "missing dependency: $path" }
}

if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
if (Test-Path $dexDir) { Remove-Item $dexDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes, $dexDir, $dest | Out-Null

Write-Host "== javac =="
$sources = Get-ChildItem (Join-Path $base "src\java") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -source 8 -target 8 -encoding UTF-8 -nowarn -classpath "$ajar;$paho" -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed rc=$LASTEXITCODE" }

Write-Host "== patch paho jar =="
Add-Type -AssemblyName System.IO.Compression.FileSystem
$patched = Join-Path $out "paho-dex.jar"
if (Test-Path $patched) { Remove-Item $patched -Force }
$srcZip = [System.IO.Compression.ZipFile]::OpenRead($paho)
try {
    $dstZip = [System.IO.Compression.ZipFile]::Open($patched, "Create")
    try {
        foreach ($entry in $srcZip.Entries) {
            if ($entry.FullName -eq "org/eclipse/paho/client/mqttv3/internal/NetworkModuleService.class") { continue }
            $new = $dstZip.CreateEntry($entry.FullName)
            $in = $entry.Open()
            $outStream = $new.Open()
            try { $in.CopyTo($outStream) } finally { $outStream.Close(); $in.Close() }
        }
    } finally { $dstZip.Dispose() }
} finally { $srcZip.Dispose() }

Write-Host "== d8 =="
$classFiles = Get-ChildItem $classes -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& java -cp $d8 com.android.tools.r8.D8 --min-api 21 --lib $ajar --output $dexDir @classFiles $patched
if ($LASTEXITCODE -ne 0) { throw "d8 failed rc=$LASTEXITCODE" }

$dex = Join-Path $dexDir "classes.dex"
if (-not (Test-Path $dex)) { throw "d8 produced no classes.dex" }
Copy-Item $dex (Join-Path $dest "dsh-kit.dex") -Force
$bytes = [System.IO.File]::ReadAllBytes((Join-Path $dest "dsh-kit.dex"))
$magic = [System.Text.Encoding]::ASCII.GetString($bytes[0..7])
Write-Host "dex magic: $magic"
if (-not $magic.StartsWith("dex")) { throw "output is not a valid dex" }
Write-Host "== done: $dest\dsh-kit.dex =="
