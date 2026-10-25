param([string]$Serial, [string]$QuestionId, [switch]$ReadResult)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'toolchain.ps1')
$tc = Initialize-RainToolchain
$workspacePath = Split-Path -Parent $PSScriptRoot
$adbPath = $tc.Adb
# Android user directory comes from toolchain configuration.
$devices = @(& $adbPath devices | ForEach-Object { if ($_ -match '^([^\s]+)\s+device$') { $Matches[1] } })
if (-not $Serial -and $devices.Count -eq 1) { $Serial = $devices[0] }
if (-not $Serial -or $Serial -notin $devices) { throw 'Select one authorized device.' }
if ($QuestionId -and $QuestionId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') {
    throw 'QuestionId must be a local question UUID.'
}
if ($ReadResult) {
    # The probe writes only comparison flags, fixed error classes and local record UUIDs.
    $raw = & $adbPath -s $Serial shell run-as cn.rainnotify.app.debug cat no_backup/question-comparison-probe.json
    if ($LASTEXITCODE -ne 0) { throw 'No comparison result is available yet.' }
    $raw | ConvertFrom-Json | ConvertTo-Json -Depth 8
} else {
    # Debug + shell permission only. The activity has GET access but no submission code path.
    $arguments = @('-s', $Serial, 'shell', 'am', 'start', '--activity-clear-top', '-n',
        'cn.rainnotify.app.debug/cn.rainnotify.app.answer.QuestionComparisonProbeActivity')
    if ($QuestionId) { $arguments += @('--es', 'question', $QuestionId) }
    & $adbPath @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Cannot launch the read-only question comparison.' }
    Write-Output 'Read-only comparison started. Use -ReadResult after it finishes; no AI run or answer submission is performed.'
}
