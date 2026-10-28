param([string]$Serial)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'toolchain.ps1')
$tc=Initialize-RainToolchain
$Serial=Select-RainDevice $tc $Serial
if ((& $tc.Adb -s $Serial shell getprop ro.build.version.sdk | Out-String).Trim() -ne '36') { throw 'Device smoke baseline requires Android 16.' }
$app=Join-Path $tc.Root 'android-app\app\build\outputs\apk\smoke\app-smoke.apk'
$test=Join-Path $tc.Root 'android-app\app\build\outputs\apk\androidTest\smoke\app-smoke-androidTest.apk'
foreach($apk in @($app,$test)) {
    $badging=(& $tc.Aapt dump badging $apk | Out-String)
    if ($LASTEXITCODE -or $badging -notmatch "package: name='cn\.rainnotify\.app\.smoke(?:\.test)?'") { throw 'Only isolated smoke packages may be installed by this script.' }
    & $tc.Adb -s $Serial install --no-streaming -r -t $apk
    if ($LASTEXITCODE) { throw 'Allow the USB installation on the unlocked device, then rerun.' }
}
$output=(& $tc.Adb -s $Serial shell am instrument -w -r cn.rainnotify.app.smoke.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | Out-String)
$exitCode=$LASTEXITCODE
$report=Join-Path $tc.Root 'android-app\app\build\device-smoke-output.txt'
[IO.File]::WriteAllText($report,$output,[Text.UTF8Encoding]::new($false))
if ($exitCode -or $output -notmatch 'OK \(\d+ tests?\)' -or $output -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[12]') {
    Write-Output $output
    throw "Device tests failed. See $report"
}
Write-Output $output
Write-Output "Device tests passed. Isolated packages retained; preview and release packages were not cleared or uninstalled. Report: $report"
