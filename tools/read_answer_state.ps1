param([switch]$IncludeAnswer)
$ErrorActionPreference = 'Stop'
$workspacePath = Split-Path -Parent $PSScriptRoot
$adbPath = Join-Path $workspacePath '.local-tools\android-sdk\platform-tools\adb.exe'
$env:ANDROID_USER_HOME = Join-Path $workspacePath '.local-tools\android-user'
$devices = @(& $adbPath devices | ForEach-Object { if ($_ -match '^([^\s]+)\s+device$') { $Matches[1] } })
if ($devices.Count -ne 1) { throw 'Connect exactly one authorized Android device.' }
# Read only our own task status. Do not use uiautomator during an accessibility run:
# its default UiAutomation connection suppresses accessibility services.
$raw = & $adbPath -s $devices[0] shell run-as cn.rainnotify.app.debug cat no_backup/last-answer-task.json
if ($LASTEXITCODE -ne 0) { throw 'No readable AI task status in this debug build.' }
$state = $raw | ConvertFrom-Json
$output = [ordered]@{phase=$state.phase; live=$state.live; message=$state.message; started_at_ms=$state.started}
if ($IncludeAnswer) { $output.answer = $state.answer }
$events = & $adbPath -s $devices[0] shell run-as cn.rainnotify.app.debug cat files/diagnostics.jsonl
if ($LASTEXITCODE -eq 0) {
    $matching = @($events | ForEach-Object {
        try { $entry = $_ | ConvertFrom-Json; if ($entry.event_id -eq $state.id -and $entry.kind -like 'answer_*') { $entry } } catch { }
    })
    $output.stages = @($matching | ForEach-Object {
        [ordered]@{kind=$_.kind; elapsed_from_first_ms=($_.elapsed_ms - $matching[0].elapsed_ms);
            interactive=$_.interactive; device_idle=$_.device_idle; source=$_.source}
    })
    $output.content_checks = @($matching | Where-Object { $_.kind -eq 'answer_content_check' } | ForEach-Object {
        [ordered]@{stage=$_.comparison_stage; content_same=$_.content_same;
            changed_fields=$_.changed_fields; asset_url_changed=$_.asset_url_changed}
    })
}
$output | ConvertTo-Json -Depth 5
