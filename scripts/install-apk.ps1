# 一键构建 + 装机（含 vivo「继续安装」确认框的自动点击）
#
# 为什么需要这个脚本：
#   1. 本机 gradle / java 都不在 PATH（真机验证时每次都要手写 JAVA_HOME）；
#   2. vivo 装了「外部来源应用」安全守护页，`adb install` 会挂住等用户点，
#      锁屏时直接返回 INSTALL_FAILED_ABORTED。这里用「后台跑 install + 轮询 dump
#      自动勾选 + 点继续」把这一套固定下来。
#
# 用法：
#   pwsh -NoProfile -File scripts\install-apk.ps1            # 构建 + 装机
#   pwsh -NoProfile -File scripts\install-apk.ps1 -NoBuild   # 只装机（用现有 APK）
param([switch]$NoBuild)

$env:JAVA_HOME = 'C:\Users\Administrator\android-dev\jdk-17.0.13+11'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$root = Split-Path -Parent $PSScriptRoot
$gradle = 'C:\Users\Administrator\android-dev\gradle-8.10.2\bin\gradle.bat'
$adb = 'C:\Users\Administrator\Android\Sdk\platform-tools\adb.exe'
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'

function Tap-Id([string]$pattern) {
    & $adb shell uiautomator dump 2>$null | Out-Null
    $xml = (& $adb shell cat /sdcard/window_dump.xml) -join ''
    $m = [regex]::Match($xml, 'resource-id="[^"]*' + $pattern + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { return $false }
    $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    & $adb shell input tap $x $y 2>$null | Out-Null
    Write-Host "  tapped $pattern at ($x,$y)"
    return $true
}

if (-not $NoBuild) {
    Push-Location $root
    & $gradle -q assembleDebug
    $rc = $LASTEXITCODE
    Pop-Location
    if ($rc -ne 0) { Write-Host "BUILD FAILED (exit=$rc)"; exit 1 }
    Write-Host ("BUILD OK  {0:N0} B" -f (Get-Item $apk).Length)
}

& $adb shell input keyevent KEYCODE_WAKEUP 2>$null | Out-Null
& $adb shell am force-stop com.trace.app 2>$null | Out-Null
Start-Sleep -Seconds 1

$job = Start-Job -ScriptBlock { param($a, $p) & $a install -r $p 2>&1 } -ArgumentList $adb, $apk
$checkbox = $false
$continued = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 1
    if (-not $checkbox) {
        if (Tap-Id 'deleted_file_state_cb') { $checkbox = $true; Start-Sleep -Seconds 1; continue }
    }
    elseif (-not $continued) {
        if (Tap-Id 'id/button1') { $continued = $true; break }
    }
}
Write-Host "checkbox=$checkbox continueBtn=$continued"
Receive-Job $job -Wait