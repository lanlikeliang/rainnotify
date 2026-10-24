param(
    [ValidateSet('KIMI','DEEPSEEK','DOUBAO')][string]$Provider = 'KIMI',
    [string]$QuestionId,
    [switch]$Delayed,
    [switch]$OpenOnly,
    [ValidateSet(0,1,2)][int]$VerifyType = 0,
    [switch]$CompatibilityOnly,
    [switch]$Stop
)
$ErrorActionPreference = 'Stop'
$workspacePath = Split-Path -Parent $PSScriptRoot
$env:ANDROID_USER_HOME = Join-Path $workspacePath '.local-tools\android-user'
$adb = Join-Path $workspacePath '.local-tools\android-sdk\platform-tools\adb.exe'
$devices = @(& $adb devices | ForEach-Object { if ($_ -match '^([^\s]+)\s+device$') { $Matches[1] } })
if ($devices.Count -ne 1) { throw 'Connect exactly one authorized Android device.' }
if ($QuestionId -and $QuestionId -notmatch '^[0-9a-f-]{36}$') { throw 'Use a local question UUID.' }
$arguments = @('-s', $devices[0], 'shell', 'am', 'start', '--activity-clear-top', '-n',
    'cn.rainnotify.app.debug/cn.rainnotify.app.answer.RehearsalProbeActivity', '--es', 'provider', $Provider)
if ($Stop) { $arguments += @('--ez', 'stop', 'true') }
if ($Delayed) { $arguments += @('--ez', 'delayed', 'true') }
if ($OpenOnly) { $arguments += @('--ez', 'open_only', 'true') }
if ($VerifyType) { $arguments += @('--ei', 'verify_type', "$VerifyType") }
if ($CompatibilityOnly) { $arguments += @('--ez', 'compatibility_only', 'true') }
if ($QuestionId) { $arguments += @('--es', 'question', $QuestionId) }
# This debug-only Activity always selects rehearsal; it has no live-submission argument.
& $adb @arguments
if ($LASTEXITCODE -ne 0) { throw 'The rehearsal probe could not start.' }
