param([string]$AdbPath, [string]$Serial)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'toolchain.ps1')
$tc = Initialize-RainToolchain
$workspacePath = Split-Path -Parent $PSScriptRoot
if (-not $AdbPath) {
    $candidate = $tc.Adb
    if (Test-Path -LiteralPath $candidate) { $AdbPath = $candidate }
    else {
        $command = Get-Command adb -ErrorAction SilentlyContinue
        if ($command) { $AdbPath = $command.Source }
    }
}
if (-not $AdbPath -or -not (Test-Path -LiteralPath $AdbPath)) {
    [pscustomobject]@{status='adb_missing';action='Prepare official Platform Tools first.'} | ConvertTo-Json
    exit 2
}
# Android user directory comes from toolchain configuration.
New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME -Force | Out-Null
$deviceLines = @(& $AdbPath devices)
if ($LASTEXITCODE -ne 0) { throw 'ADB device enumeration failed.' }
$devices = @($deviceLines | ForEach-Object {
    if ($_ -match '^([^\s]+)\s+(device|unauthorized|offline)$') {
        [pscustomobject]@{serial=$Matches[1];state=$Matches[2]}
    }
})
if (-not $Serial -and $devices.Count -eq 1) { $Serial = $devices[0].serial }
if (-not $Serial) {
    [pscustomobject]@{status=if($devices.Count -eq 0){'no_device'}else{'select_device'};devices=$devices} | ConvertTo-Json -Depth 3
    exit 3
}
$selected = $devices | Where-Object {$_.serial -eq $Serial}
if (-not $selected -or $selected.state -ne 'device') {
    [pscustomobject]@{status=if($selected){$selected.state}else{'device_not_found'};action='Unlock the phone and approve USB debugging.'} | ConvertTo-Json
    exit 4
}
$properties = @{}
foreach ($property in @('ro.product.manufacturer','ro.product.model','ro.build.version.release','ro.build.version.sdk','ro.build.version.incremental')) {
    $value = (& $AdbPath -s $Serial shell getprop $property | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Cannot read device property $property" }
    $properties[$property] = $value
}
[pscustomobject]@{status='connected';properties=$properties;android16=($properties['ro.build.version.sdk'] -eq '36')} | ConvertTo-Json -Depth 3
