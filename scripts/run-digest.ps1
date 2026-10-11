# 跑一次「写今天总结」，把最新一条 daily_digest 打出来。
#
# 为什么不用坐标硬点：首页总结卡高度随正文变化，btnDigest 经常掉出可视区，
# 而且用户随时可能在用手机（通知栏/锁屏会抢焦点）。这里固定成「先滑到底 →
# 再一步一步往上找按钮」的闭环。
#
# 用法：pwsh -NoProfile -File scripts\run-digest.ps1 [-WaitSec 150]
param([int]$WaitSec = 150)

$adb = 'C:\Users\Administrator\Android\Sdk\platform-tools\adb.exe'
$pkg = 'com.trace.app'

function Tap-Id([string]$id) {
    & $adb shell uiautomator dump 2>$null | Out-Null
    $xml = (& $adb shell cat /sdcard/window_dump.xml) -join ''
    $m = [regex]::Match($xml, 'resource-id="' + $pkg + ':id/' + $id + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { return $false }
    $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    & $adb shell input tap $x $y 2>$null | Out-Null
    Write-Host "  tapped $id at ($x,$y)"
    return $true
}

& $adb shell input keyevent KEYCODE_WAKEUP 2>$null | Out-Null
& $adb shell input keyevent 4 2>$null | Out-Null   # 关掉可能开着的通知栏
Start-Sleep -Seconds 1
& $adb shell am start -n "$pkg/.MainActivity" -f 0x04000000 2>$null | Out-Null
Start-Sleep -Seconds 5
$focus = (& $adb shell dumpsys window | Select-String 'mCurrentFocus') -join ' '
Write-Host "focus: $focus"
if ($focus -notmatch [regex]::Escape($pkg)) { Write-Host "!! $pkg 不在前台，先处理手机状态"; exit 1 }

& $adb logcat -c 2>$null | Out-Null
# 先滑到底部，再一步步往上找按钮
for ($i = 0; $i -lt 4; $i++) { & $adb shell input swipe 608 2100 608 900 180 2>$null | Out-Null; Start-Sleep -Milliseconds 500 }
$ok = $false
for ($i = 0; $i -lt 14; $i++) {
    if (Tap-Id 'btnDigest') { $ok = $true; break }
    & $adb shell input swipe 608 900 608 1400 160 2>$null | Out-Null
    Start-Sleep -Milliseconds 700
}
if (-not $ok) { Write-Host '!! 没找到「写今天总结」按钮'; exit 1 }

Write-Host "等待生成 ${WaitSec}s ..."
Start-Sleep -Seconds $WaitSec
& $adb logcat -d -v time -s TraceVlm:V 2>$null | Select-String 'chat 生成|闸门' | Select-Object -Last 2
& $adb shell "run-as $pkg sqlite3 /data/data/$pkg/databases/trace.db `"SELECT id||' | '||model||' | '||day FROM daily_digest ORDER BY id DESC LIMIT 1;`"" 2>$null
& $adb shell "run-as $pkg sqlite3 /data/data/$pkg/databases/trace.db `"SELECT text FROM daily_digest ORDER BY id DESC LIMIT 1;`"" 2>$null
& $adb shell "run-as $pkg sqlite3 /data/data/$pkg/databases/trace.db `"SELECT json FROM daily_digest ORDER BY id DESC LIMIT 1;`"" 2>$null