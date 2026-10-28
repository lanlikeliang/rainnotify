param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'toolchain.ps1')
$tc = Initialize-RainToolchain
$workspacePath = Split-Path -Parent $PSScriptRoot
# Android user directory comes from toolchain configuration.
$adb = $tc.Adb
$component = 'cn.rainnotify.app.debug/cn.rainnotify.app.answer.KimiAccessibilityService'
$enabled = (& $adb shell settings get secure enabled_accessibility_services | Out-String).Trim().Split(':') -contains $component
$dump = & $adb shell dumpsys accessibility
$crashed = @($dump | Select-String 'Crashed services:.*rainnotify').Count -gt 0
$bound = @($dump | Select-String 'Bound services:.*雨课堂').Count -gt 0
[ordered]@{enabled=$enabled; bound=$bound; crashed=$crashed} | ConvertTo-Json
# Only this app's lifecycle events are emitted; never copy system-wide accessibility UI dumps during a running task.
& $adb shell run-as cn.rainnotify.app.debug cat files/diagnostics.jsonl | ForEach-Object {
    try { $entry = $_ | ConvertFrom-Json; if ($entry.kind -like 'accessibility_*') {
        [pscustomobject]@{kind=$entry.kind; timestamp_ms=$entry.timestamp_ms; interactive=$entry.interactive}
    } } catch { }
} | Select-Object -Last 12 | Format-Table
