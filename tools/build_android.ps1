param([string]$JdkPath, [switch]$GenerateWrapper, [switch]$Lint, [switch]$UnitTests)
$ErrorActionPreference = 'Stop'
$workspacePath = Split-Path -Parent $PSScriptRoot
if (-not $JdkPath) {
    $localJdk = Get-ChildItem -LiteralPath (Join-Path $workspacePath '.local-tools') -Directory -Filter 'jdk-17*' |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\jlink.exe') } | Select-Object -First 1
    if ($localJdk) {
        $JdkPath = $localJdk.FullName
    } elseif ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\jlink.exe'))) {
        $JdkPath = $env:JAVA_HOME
    } else { throw 'Supply -JdkPath pointing to a JDK 17 installation.' }
}
foreach ($requiredTool in @('java.exe', 'javac.exe', 'jlink.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $JdkPath ("bin\$requiredTool")))) {
        throw "The selected JDK is incomplete: missing $requiredTool"
    }
}
$gradlePath = Join-Path $workspacePath '.local-tools\gradle-8.13\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradlePath)) { throw 'Verified Gradle 8.13 is missing.' }
$mappedDrive = $null
try {
    # JDK 17's Windows launcher can misread UTF-8 Gradle worker argfiles containing
    # non-ASCII paths. Use a temporary ASCII alias to this same verified workspace.
    # No checkout is copied or moved; only an unused drive mapping is removed below.
    if ($workspacePath -match '[^\x00-\x7F]') {
        $originalWorkspace = (Resolve-Path -LiteralPath $workspacePath).Path
        $driveName = @('R:', 'S:', 'T:', 'U:', 'V:', 'W:', 'X:', 'Y:', 'Z:') |
            Where-Object { -not (Test-Path -LiteralPath ($_ + '\')) -and -not (Get-PSDrive -Name $_.TrimEnd(':') -ErrorAction SilentlyContinue) } |
            Select-Object -First 1
        if (-not $driveName) { throw 'No unused drive is available for the temporary ASCII build path.' }
        & subst.exe $driveName $originalWorkspace
        if ($LASTEXITCODE -ne 0) { throw 'Cannot create the temporary build drive.' }
        $mappedDrive = $driveName
        $workspacePath = $driveName + '\'
        if ($JdkPath.StartsWith($originalWorkspace + '\', [StringComparison]::OrdinalIgnoreCase)) {
            $JdkPath = Join-Path $workspacePath $JdkPath.Substring($originalWorkspace.Length + 1)
        }
        $gradlePath = Join-Path $workspacePath '.local-tools\gradle-8.13\bin\gradle.bat'
    }
    $env:JAVA_HOME = $JdkPath
    $env:ANDROID_HOME = Join-Path $workspacePath '.local-tools\android-sdk'
    $env:ANDROID_USER_HOME = Join-Path $workspacePath '.local-tools\android-user'
    $env:GRADLE_USER_HOME = Join-Path $workspacePath '.local-tools\gradle-home'
    $env:PATH = (Join-Path $JdkPath 'bin') + [IO.Path]::PathSeparator + $env:PATH
    $projectPath = Join-Path $workspacePath 'android-app'
    $arguments = @('--project-dir', $projectPath, '--no-daemon', '--console=plain', '--max-workers=2', ':app:assembleDebug')
    if ($Lint) { $arguments += ':app:lintDebug' }
    if ($UnitTests) { $arguments += ':app:testDebugUnitTest' }
    if ($GenerateWrapper) {
        $arguments += @('wrapper', '--gradle-version', '8.13', '--distribution-type', 'bin',
            '--gradle-distribution-sha256-sum', '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78',
            '--no-validate-url')
    }
    & $gradlePath @arguments
    $buildExitCode = $LASTEXITCODE
} finally {
    if ($mappedDrive) { & subst.exe $mappedDrive /D }
}
exit $buildExitCode
