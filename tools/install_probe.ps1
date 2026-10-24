param([string]$Serial, [switch]$OpenLogin, [switch]$OpenQuestions)
$ErrorActionPreference = 'Stop'
$workspacePath = Split-Path -Parent $PSScriptRoot
$adbPath = Join-Path $workspacePath '.local-tools\android-sdk\platform-tools\adb.exe'
$apkPath = Join-Path $workspacePath 'android-app\app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $apkPath)) { throw 'Build the debug APK successfully before installation.' }
$env:ANDROID_USER_HOME = Join-Path $workspacePath '.local-tools\android-user'
$lines = @(& $adbPath devices)
$connected = @($lines | ForEach-Object { if ($_ -match '^([^\s]+)\s+device$') { $Matches[1] } })
if (-not $Serial -and $connected.Count -eq 1) { $Serial = $connected[0] }
if (-not $Serial -or $Serial -notin $connected) { throw 'Select one authorized connected device using -Serial.' }
$sdkVersion = (& $adbPath -s $Serial shell getprop ro.build.version.sdk | Out-String).Trim()
if ($sdkVersion -ne '36') { throw 'This feasibility build targets Android 16 / API 36.' }
& $adbPath -s $Serial install -r -t $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed. Check the phone for a USB installation confirmation.' }
$launchArguments = @('-s', $Serial, 'shell', 'am', 'start', '-n', 'cn.rainnotify.app.debug/cn.rainnotify.app.MainActivity')
if ($OpenLogin) { $launchArguments += @('--ez', 'open_login_probe', 'true') }
if ($OpenQuestions) { $launchArguments += @('--ez', 'open_questions_probe', 'true') }
& $adbPath @launchArguments
if ($LASTEXITCODE -ne 0) { throw 'Installed APK could not be opened.' }
