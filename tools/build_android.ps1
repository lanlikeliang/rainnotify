param(
    [ValidateSet('Debug','Release')][string]$Variant = 'Debug',
    [string]$JdkPath, [string]$SdkPath,
    [switch]$Lint, [switch]$UnitTests, [switch]$DeviceTests,
    [switch]$WriteVerification, [switch]$Offline,
    [int]$VersionCode = 0, [string]$VersionName
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'toolchain.ps1')
$tc = Initialize-RainToolchain -JdkPath $JdkPath -SdkPath $SdkPath
$project = Join-Path $tc.Root 'android-app'
$wrapper = Join-Path $project 'gradlew.bat'
$javaVersion = (& (Join-Path $tc.Jdk 'bin\java.exe') -version 2>&1 | Out-String)
if ($javaVersion -notmatch 'version "17\.') { throw 'The pinned build baseline requires JDK 17.' }
$commit = (& git -C $tc.Root rev-parse HEAD | Out-String).Trim()
if ($LASTEXITCODE) { throw 'A Git checkout is required for traceable builds.' }
$gradleArgs = @('--project-dir', $project, '--no-daemon', '--console=plain', '--max-workers=2', "-PgitCommit=$commit", ":app:assemble$Variant")
if ($Lint) { $gradleArgs += ":app:lint$Variant" }
if ($UnitTests) { $gradleArgs += ":app:test${Variant}UnitTest" }
if ($DeviceTests) { $gradleArgs += ':app:connectedDebugAndroidTest' }
if ($Offline) { $gradleArgs += '--offline' }
if ($VersionCode -gt 0) { $gradleArgs += "-PreleaseVersionCode=$VersionCode" }
if ($VersionName) {
    if ($VersionName -notmatch '^[0-9A-Za-z.+-]+$') { throw 'Invalid version name' }
    $gradleArgs += "-PreleaseVersionName=$VersionName"
}
if ($WriteVerification) { $gradleArgs += @('--write-verification-metadata', 'sha256') }
Push-Location $project
try { & $wrapper @gradleArgs; $code = $LASTEXITCODE } finally { Pop-Location }
if ($code -ne 0) { throw "Gradle failed with exit code $code" }
$output = if ($Variant -eq 'Debug') { 'app\build\outputs\apk\debug\app-debug.apk' } else { 'app\build\outputs\apk\release\app-release-unsigned.apk' }
$apk = Join-Path $project $output
$dirty = [bool]((& git -C $tc.Root status --porcelain | Out-String).Trim())
$receipt = [ordered]@{variant=$Variant;commit=$commit;dirty=$dirty;apk=$apk;sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash;unitTests=[bool]$UnitTests;lint=[bool]$Lint;deviceTests=[bool]$DeviceTests;utc=[DateTime]::UtcNow.ToString('o')}
[IO.File]::WriteAllText(($apk + '.build.json'), ($receipt | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
$receipt | ConvertTo-Json
