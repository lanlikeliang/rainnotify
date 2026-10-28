# Shared process-local configuration. No global environment or registry edits.
function Initialize-RainToolchain {
    param([string]$JdkPath, [string]$SdkPath)
    $root = Split-Path -Parent $PSScriptRoot
    $configFile = Join-Path $root '.toolchain.local.json'
    $config = if (Test-Path -LiteralPath $configFile) { Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json } else { $null }
    if (-not $JdkPath) { $JdkPath = if ($config.JdkPath) { $config.JdkPath } else { $env:JAVA_HOME } }
    if (-not $SdkPath) { $SdkPath = if ($config.SdkPath) { $config.SdkPath } else { $env:ANDROID_HOME } }
    foreach ($name in @('java.exe','javac.exe','jlink.exe','keytool.exe')) {
        if (-not $JdkPath -or -not (Test-Path -LiteralPath (Join-Path $JdkPath "bin\$name"))) { throw 'Configure a complete JDK 17 using -JdkPath or .toolchain.local.json.' }
    }
    if (-not $SdkPath -or -not (Test-Path -LiteralPath (Join-Path $SdkPath 'platforms\android-36\android.jar'))) { throw 'Configure SDK Platform 36 using -SdkPath or .toolchain.local.json.' }
    $env:JAVA_HOME = $JdkPath
    $env:ANDROID_HOME = $SdkPath
    if ($config.GradleUserHome) { $env:GRADLE_USER_HOME = $config.GradleUserHome }
    if ($config.AndroidUserHome) { $env:ANDROID_USER_HOME = $config.AndroidUserHome }
    if ($config.DebugKeystorePath) { $env:RAINNOTIFY_DEBUG_KEYSTORE = $config.DebugKeystorePath }
    $env:PATH = (Join-Path $JdkPath 'bin') + [IO.Path]::PathSeparator + $env:PATH
    return [pscustomobject]@{Root=$root;Jdk=$JdkPath;Sdk=$SdkPath;Adb=(Join-Path $SdkPath 'platform-tools\adb.exe');Aapt=(Join-Path $SdkPath 'build-tools\35.0.0\aapt2.exe')}
}
function Select-RainDevice {
    param($Toolchain, [string]$Serial)
    $devices = @(& $Toolchain.Adb devices | ForEach-Object { if ($_ -match '^(\S+)\s+device$') { $Matches[1] } })
    if (-not $Serial -and $devices.Count -eq 1) { $Serial = $devices[0] }
    if (-not $Serial -or $Serial -notin $devices) { throw 'Connect and authorize one device, or pass -Serial explicitly.' }
    return $Serial
}
