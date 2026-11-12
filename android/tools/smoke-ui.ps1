# 模拟器冒烟：创建 -> 左滑 -> 删除 -> 撤销。坐标从 uiautomator 节点里现取。
param(
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$tmp = Join-Path $root 'shots\ui-tmp.xml'

function Get-Center([string]$text) {
    & $Adb shell uiautomator dump /sdcard/x.xml 2>&1 | Out-Null
    & $Adb shell cat /sdcard/x.xml > $tmp
    $raw = Get-Content $tmp -Raw -Encoding UTF8
    $pattern = 'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    foreach ($m in [regex]::Matches($raw, $pattern)) {
        if ($m.Groups[1].Value -eq $text) {
            $x = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
            $y = ([int]$m.Groups[3].Value + [int]$m.Groups[5].Value) / 2
            return @([int]$x, [int]$y)
        }
    }
    return $null
}

function Tap-Node([string]$text) {
    $c = Get-Center $text
    if (-not $c) {
        Write-Host "MISS: $text"
        return $false
    }
    & $Adb shell input tap $c[0] $c[1] | Out-Null
    Write-Host "TAP: $text @ $($c[0]),$($c[1])"
    return $true
}

Write-Host '== ensure app foreground =='
& $Adb shell am start -n com.todo.android/.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 3

Write-Host '== create task =='
$field = Get-Center '输入标题，回车快速创建（截止默认 7 天后）'
if ($field) { & $Adb shell input tap $field[0] $field[1] | Out-Null }
Start-Sleep -Milliseconds 500
1..14 | ForEach-Object { & $Adb shell input keyevent 67 | Out-Null }
& $Adb shell input text 'Final%sTest' | Out-Null
& $Adb shell input keyevent 66 | Out-Null
Start-Sleep -Milliseconds 1200
& $Adb shell input keyevent 111 | Out-Null
Start-Sleep -Milliseconds 900
$row = Get-Center 'Final Test'
Write-Host "ROW: $($row -join ',')"
if (-not $row) { Write-Host 'FAILED: task row not found'; exit 1 }

Write-Host '== swipe left =='
& $Adb shell input swipe 950 $row[1] 150 $row[1] 200 | Out-Null
Start-Sleep -Milliseconds 1400
& $Adb shell screencap -p /sdcard/sheet.png | Out-Null
& $Adb pull /sdcard/sheet.png (Join-Path $root 'shots\emul-sheet2.png') 2>&1 | Out-Null

Write-Host '== delete =='
# ModalBottomSheet 是独立窗口，uiautomator dump 只给主窗口，所以删除按钮用固定坐标。
& $Adb shell input tap 540 2229 | Out-Null
Start-Sleep -Milliseconds 600
$undo = Get-Center '撤销'
Write-Host "UNDO: $($undo -join ',')"
if (-not $undo) { Write-Host 'FAILED: undo button not found'; exit 1 }

Write-Host '== tap undo =='
& $Adb shell input tap $undo[0] $undo[1] | Out-Null
Start-Sleep -Milliseconds 1300
$restored = Get-Center 'Final Test'
Write-Host "RESTORED: $($restored -join ',')"

Write-Host '== crash buffer =='
& $Adb logcat -b crash -d
