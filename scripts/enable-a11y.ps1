# enable-a11y.ps1 —— 一键把 Trace 的无障碍截屏服务打开（整屏、免投屏授权弹窗）
#
# 为什么需要脚本：Android 没有「应用自己开无障碍」的合法 API（设计上不开放），
# 而且**每次 adb install -r 重装 App，系统都会把该 App 的无障碍服务自动关掉**。
# 开发期极烦人，所以固化成一键脚本，装完补一刀。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\enable-a11y.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\enable-a11y.ps1 -Off
#
# 原理：用 adb shell（uid 2000，持有 WRITE_SECURE_SETTINGS）直接写 Secure 设置。
# 先读出已有列表再追加，不会清掉用户其它无障碍服务。

param(
    [string]$Adb = 'C:\Users\Administrator\Android\Sdk\platform-tools\adb.exe',
    [string]$Svc = 'com.trace.app/com.trace.app.vlm.TraceScreenAccessibilityService',
    [switch]$Off
)

if (-not (Test-Path $Adb)) { throw "adb not found: $Adb" }

$pkg = ($Svc -split '/')[0]
$raw = (& $Adb shell settings get secure enabled_accessibility_services) -join ''
$cur = @()
if ($raw -and $raw.Trim() -ne 'null') {
    $cur = $raw.Trim() -split ':' | Where-Object { $_ -and $_.Trim() -ne '' }
}
$kept = $cur | Where-Object { $_ -notlike "$pkg/*" }

if ($Off) {
    $new = $kept
    Write-Host "关闭：$Svc"
} else {
    $new = @($kept) + @($Svc)
    Write-Host "开启：$Svc"
}

if ($new.Count -eq 0) {
    & $Adb shell settings delete secure enabled_accessibility_services | Out-Null
} else {
    & $Adb shell settings put secure enabled_accessibility_services "'$($new -join ':')'" | Out-Null
}
if ($Off -and $new.Count -eq 0) {
    & $Adb shell settings put secure accessibility_enabled 0 | Out-Null
} else {
    & $Adb shell settings put secure accessibility_enabled 1 | Out-Null
}

Start-Sleep -Milliseconds 800
$after = (& $Adb shell settings get secure enabled_accessibility_services) -join ''
$flag = (& $Adb shell settings get secure accessibility_enabled) -join ''
Write-Host "accessibility_enabled = $flag"
Write-Host "enabled_accessibility_services = $after"
Write-Host "本服务在里面？ $([bool]($after -like "*$Svc*"))"
Write-Host ""
Write-Host "若手机上弹出「允许使用无障碍服务」确认框，请点允许。"
Write-Host "验证：adb shell dumpsys accessibility | Select-String trace.app"