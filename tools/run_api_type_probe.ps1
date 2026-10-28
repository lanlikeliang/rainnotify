param([Parameter(Mandatory=$true)][ValidateSet(3,4,5)][int]$Type)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'toolchain.ps1')
$tc = Initialize-RainToolchain
$root = Split-Path -Parent $PSScriptRoot
# Android user directory comes from toolchain configuration.
$adb = $tc.Adb
$devices = @(& $adb devices | ForEach-Object { if ($_ -match '^([^\s]+)\s+device$') { $Matches[1] } })
if ($devices.Count -ne 1) { throw 'Connect one authorized device.' }
# Uses the phone's configured API, never reads its key, and cannot submit classroom answers.
& $adb -s $devices[0] shell am start -n cn.rainnotify.app.debug/cn.rainnotify.app.answer.ApiTypeProbeActivity --ei sample_type $Type
if ($LASTEXITCODE -ne 0) { throw 'Could not open offline exercise.' }
