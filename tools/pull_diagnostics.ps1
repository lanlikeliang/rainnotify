param([string]$Serial)
$ErrorActionPreference = 'Stop'
$workspacePath = Split-Path -Parent $PSScriptRoot
$adbPath = Join-Path $workspacePath '.local-tools\android-sdk\platform-tools\adb.exe'
$env:ANDROID_USER_HOME = Join-Path $workspacePath '.local-tools\android-user'
$lines = @(& $adbPath devices)
$connected = @($lines | ForEach-Object { if ($_ -match '^([^\s]+)\s+device$') { $Matches[1] } })
if (-not $Serial -and $connected.Count -eq 1) { $Serial = $connected[0] }
if (-not $Serial -or $Serial -notin $connected) { throw 'Select one authorized connected device using -Serial.' }
# Read only this project's debug App; do not export WeChat or system-wide logs.
$reportLines = @(& $adbPath -s $Serial exec-out run-as 'cn.rainnotify.app.debug' cat 'files/diagnostics.jsonl')
if ($LASTEXITCODE -ne 0) { throw 'Cannot read diagnostics. Open the debug App first.' }
$reportFolder = Join-Path $workspacePath 'diagnostic-reports'
New-Item -ItemType Directory -Path $reportFolder -Force | Out-Null
$stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
$reportPath = Join-Path $reportFolder ("probe-$stamp-utc.jsonl")
[IO.File]::WriteAllText($reportPath, ($reportLines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
[pscustomobject]@{report=$reportPath;lines=$reportLines.Count} | ConvertTo-Json
