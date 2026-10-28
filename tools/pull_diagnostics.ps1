param([string]$Serial, [ValidateSet('Debug','Release')][string]$Variant='Debug', [string]$ReportPath)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if ($ReportPath) {
    & python (Join-Path $PSScriptRoot 'analyze_diagnostics.py') $ReportPath
    if ($LASTEXITCODE) { throw 'Diagnostic analysis failed.' }
    exit 0
}
if ($Variant -eq 'Release') { throw 'Release does not allow run-as. In the app use Settings > Notifications > Export diagnostics, transfer that report, then pass -ReportPath.' }
. (Join-Path $PSScriptRoot 'toolchain.ps1')
$tc = Initialize-RainToolchain
$Serial = Select-RainDevice $tc $Serial
$lines = @(& $tc.Adb -s $Serial exec-out run-as cn.rainnotify.app.debug cat files/diagnostics.jsonl)
if ($LASTEXITCODE) { throw 'Cannot read debug diagnostics. Open the debug app first.' }
$folder = Join-Path $root 'diagnostic-reports'
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$path = Join-Path $folder ('probe-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-utc.jsonl')
[IO.File]::WriteAllText($path, ($lines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
[pscustomobject]@{report=$path;lines=$lines.Count} | ConvertTo-Json
