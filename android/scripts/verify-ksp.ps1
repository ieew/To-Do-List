# Verify that KSP/Hilt generated code actually exists for every module/variant.
#
# Background: KSP can report UP-TO-DATE while build/generated/ksp stays empty,
# producing an APK that compiles but crashes at runtime with
# "NoSuchMethodException: <init> []" for the affected @HiltViewModel.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\verify-ksp.ps1
#        powershell -ExecutionPolicy Bypass -File scripts\verify-ksp.ps1 -Variants debug
param(
    [string[]]$Variants = @('debug', 'release')
)

$root = Split-Path -Parent $PSScriptRoot
$problems = New-Object System.Collections.Generic.List[string]

$moduleDirs = @()
foreach ($group in @('core', 'feature')) {
    $groupPath = Join-Path $root $group
    if (Test-Path $groupPath) {
        $moduleDirs += Get-ChildItem -Path $groupPath -Directory
    }
}
$moduleDirs += Get-Item $root

foreach ($dir in $moduleDirs) {
    $mainSources = Join-Path $dir.FullName 'src\main'
    if (-not (Test-Path $mainSources)) { continue }

    $sources = Get-ChildItem -Path $mainSources -Recurse -Filter '*.kt' -ErrorAction SilentlyContinue
    if (-not $sources) { continue }

    # @HiltViewModel 需要 Xxx_HiltModules.java；@HiltWorker 需要 Xxx_*Factory*.java
    $viewModels = New-Object System.Collections.Generic.List[string]
    $workers = New-Object System.Collections.Generic.List[string]
    foreach ($src in $sources) {
        $text = Get-Content -Path $src.FullName -Raw
        foreach ($match in [regex]::Matches($text, '@HiltViewModel[\s\S]{0,400}?class\s+(\w+)')) {
            $viewModels.Add($match.Groups[1].Value)
        }
        foreach ($match in [regex]::Matches($text, '@HiltWorker[\s\S]{0,400}?class\s+(\w+)')) {
            $workers.Add($match.Groups[1].Value)
        }
    }
    if ($viewModels.Count -eq 0 -and $workers.Count -eq 0) { continue }

    foreach ($variant in $Variants) {
        $genRoot = Join-Path $dir.FullName "build\generated\ksp\$variant"
        if (-not (Test-Path $genRoot)) {
            $problems.Add("$($dir.Name) [$variant]: build\generated\ksp\$variant is missing")
            continue
        }

        $files = @(Get-ChildItem -Path $genRoot -Recurse -Filter '*.java' -ErrorAction SilentlyContinue)
        if ($files.Count -eq 0) {
            $problems.Add("$($dir.Name) [$variant]: generated dir is empty")
            continue
        }

        foreach ($name in $viewModels) {
            if (-not ($files | Where-Object { $_.Name -eq "$($name)_HiltModules.java" })) {
                $problems.Add("$($dir.Name) [$variant]: missing $($name)_HiltModules.java")
            }
        }
        foreach ($name in $workers) {
            if (-not ($files | Where-Object { $_.Name -like "$($name)_*Factory*.java" })) {
                $problems.Add("$($dir.Name) [$variant]: missing $($name)_*Factory*.java")
            }
        }
    }
}

if ($problems.Count -gt 0) {
    Write-Host 'KSP VERIFY FAILED' -ForegroundColor Red
    $problems | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    Write-Host 'Fix: delete every <module>\build\generated\ksp and rebuild.' -ForegroundColor Yellow
    exit 1
}

Write-Host "KSP VERIFY OK ($($Variants -join ', '))" -ForegroundColor Green
exit 0
