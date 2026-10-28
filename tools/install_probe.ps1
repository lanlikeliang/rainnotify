param([string]$ApkPath, [string]$Serial, [switch]$OpenLogin, [switch]$OpenQuestions)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'toolchain.ps1')
$tc = Initialize-RainToolchain
if (-not $ApkPath) { $ApkPath = Join-Path $tc.Root 'android-app\app\build\outputs\apk\debug\app-debug.apk' }
$ApkPath = (Resolve-Path -LiteralPath $ApkPath).Path
$badging = (& $tc.Aapt dump badging $ApkPath | Out-String)
if ($LASTEXITCODE -ne 0 -or $badging -notmatch "package: name='(cn\.rainnotify\.app(?:\.debug)?)' versionCode='(\d+)' versionName='([^']+)'") { throw 'APK is not a recognized RainNotify package.' }
$package = $Matches[1]; $versionCode = $Matches[2]; $versionName = $Matches[3]
$Serial = Select-RainDevice $tc $Serial
$sdk = (& $tc.Adb -s $Serial shell getprop ro.build.version.sdk | Out-String).Trim()
if ($sdk -ne '36') { throw 'This validation baseline requires Android 16 / API 36.' }
& $tc.Adb -s $Serial install --no-streaming -r $ApkPath
if ($LASTEXITCODE) { throw 'Installation failed; check device authorization, signature and version.' }
$launch = @('-s',$Serial,'shell','am','start','-W','-n',"$package/cn.rainnotify.app.MainActivity")
if ($OpenLogin) { $launch += @('--es','tab','account') }
if ($OpenQuestions) { $launch += @('--es','tab','question') }
& $tc.Adb @launch
if ($LASTEXITCODE) { throw 'Installed app could not be opened.' }
[pscustomobject]@{package=$package;versionCode=$versionCode;versionName=$versionName} | ConvertTo-Json
