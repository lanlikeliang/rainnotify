param([Parameter(Mandatory=$true)][string]$SigningDirectory, [string]$ApkPath)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'toolchain.ps1')
. (Join-Path $PSScriptRoot 'signing.ps1')
$tc = Initialize-RainToolchain
if (-not $ApkPath) { $ApkPath = Join-Path $tc.Root 'android-app\app\build\outputs\apk\release\app-release-unsigned.apk' }
$status = (& git -C $tc.Root status --porcelain | Out-String).Trim()
if ($status) { throw 'Commit reviewed source changes before creating a traceable signed release.' }
$commit = (& git -C $tc.Root rev-parse HEAD | Out-String).Trim()
$receiptPath = $ApkPath + '.build.json'
if (-not (Test-Path -LiteralPath $receiptPath)) { throw 'Build through build_android.ps1 to create the build receipt first.' }
$receipt = Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json
if ($receipt.commit -ne $commit -or $receipt.dirty -or $receipt.variant -ne 'Release' -or -not $receipt.unitTests -or -not $receipt.lint -or $receipt.sha256 -ne (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash) {
    throw 'Release must be built from the current clean commit with -Lint -UnitTests; artifact checksum must match.'
}
& python (Join-Path $PSScriptRoot 'check_lint.py') (Join-Path $tc.Root 'android-app\app\build\reports\lint-results-release.xml')
if ($LASTEXITCODE) { throw 'Release lint gate failed.' }
$badging = (& $tc.Aapt dump badging $ApkPath | Out-String)
if ($LASTEXITCODE -or $badging -notmatch "package: name='cn\.rainnotify\.app' versionCode='(\d+)' versionName='([^']+)'") { throw 'Expected unsigned release package cn.rainnotify.app.' }
$versionCode=$Matches[1]; $versionName=$Matches[2]
if ($badging -match 'application-debuggable') { throw 'Cannot distribute a debuggable APK as release.' }
$manifest = (& $tc.Aapt dump xmltree --file AndroidManifest.xml $ApkPath | Out-String)
if ($LASTEXITCODE -or $manifest -match '(?:RehearsalProbeActivity|QuestionComparisonProbeActivity|ApiTypeProbeActivity|android\.permission\.DUMP)') { throw 'Release manifest contains a debug entry point or could not be inspected.' }
$folder=Join-Path $tc.Root "dist\$versionName-$versionCode"
if (Test-Path -LiteralPath $folder) { throw 'Distribution already exists; use a new version/build or inspect the existing result.' }
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$output=Join-Path $folder "RainNotify-$versionName-$versionCode.apk"
$signer=Join-Path $tc.Sdk 'build-tools\35.0.0\apksigner.bat'
try {
    $env:RAINNOTIFY_SIGNING_PASSWORD=Get-RainSigningPassword $SigningDirectory
    & $signer sign --ks (Join-Path $SigningDirectory 'rainnotify-release.p12') --ks-key-alias rainnotify-release --ks-pass env:RAINNOTIFY_SIGNING_PASSWORD --key-pass env:RAINNOTIFY_SIGNING_PASSWORD --out $output $ApkPath
    if ($LASTEXITCODE) { throw 'APK signing failed.' }
} finally { Remove-Item Env:\RAINNOTIFY_SIGNING_PASSWORD -ErrorAction SilentlyContinue }
$verification=(& $signer verify --verbose --print-certs $output | Out-String)
if ($LASTEXITCODE -or $verification -notmatch 'certificate SHA-256 digest: ([0-9a-f]+)') { throw 'Signed APK verification failed.' }
$certificate=$Matches[1]
& (Join-Path $tc.Sdk 'build-tools\35.0.0\zipalign.exe') -c -P 16 4 $output
if ($LASTEXITCODE) { throw 'APK alignment validation failed.' }
$hash=(Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
$metadata=[ordered]@{package='cn.rainnotify.app';versionCode=[int]$versionCode;versionName=$versionName;commit=$commit;sha256=$hash;certificateSha256=$certificate;minSdk=36;targetSdk=36;debuggable=$false;classroomAcceptance='pending';createdUtc=[DateTime]::UtcNow.ToString('o')}
[IO.File]::WriteAllText((Join-Path $folder 'APK-info.json'),($metadata | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $folder 'SHA256SUMS.txt'),"$hash  $([IO.Path]::GetFileName($output))`n",[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $folder 'signature-verification.txt'),$verification,[Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $tc.Root 'docs\INSTALL.md') -Destination (Join-Path $folder 'INSTALL.md')
Copy-Item -LiteralPath (Join-Path $tc.Root 'docs\VALIDATION.md') -Destination (Join-Path $folder 'VALIDATION.md')
Copy-Item -LiteralPath $receiptPath -Destination (Join-Path $folder 'build-receipt.json')
Write-Output ($metadata | ConvertTo-Json)
Write-Output "Signed distribution: $folder"
