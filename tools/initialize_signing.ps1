param([Parameter(Mandatory=$true)][string]$SigningDirectory)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'toolchain.ps1')
$tc = Initialize-RainToolchain
$directory = [IO.Path]::GetFullPath($SigningDirectory)
if ($directory.StartsWith($tc.Root + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Keep release signing material outside the repository.' }
if (Test-Path -LiteralPath $directory) { throw 'Signing directory already exists. Never replace an existing release key.' }
New-Item -ItemType Directory -Path $directory | Out-Null
$acl = Get-Acl -LiteralPath $directory
$acl.SetAccessRuleProtection($true, $false)
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$rule = [Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
$acl.AddAccessRule($rule)
Set-Acl -LiteralPath $directory -AclObject $acl
$bytes = New-Object byte[] 48
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes); $rng.Dispose()
$env:RAINNOTIFY_SIGNING_PASSWORD = [Convert]::ToBase64String($bytes)
try {
    $secure = ConvertTo-SecureString $env:RAINNOTIFY_SIGNING_PASSWORD -AsPlainText -Force
    $protected = ConvertFrom-SecureString $secure
    [IO.File]::WriteAllText((Join-Path $directory 'password.dpapi'), $protected, [Text.UTF8Encoding]::new($false))
    & (Join-Path $tc.Jdk 'bin\keytool.exe') -genkeypair -alias rainnotify-release -keyalg RSA -keysize 3072 -validity 10000 -storetype PKCS12 -keystore (Join-Path $directory 'rainnotify-release.p12') -storepass:env RAINNOTIFY_SIGNING_PASSWORD -keypass:env RAINNOTIFY_SIGNING_PASSWORD -dname 'CN=RainNotify, OU=Android Release, O=RainNotify'
    if ($LASTEXITCODE) { throw 'Release key generation failed; preserve directory for diagnosis.' }
} finally { Remove-Item Env:\RAINNOTIFY_SIGNING_PASSWORD -ErrorAction SilentlyContinue; if($secure){$secure.Dispose()}; [Array]::Clear($bytes,0,$bytes.Length) }
Write-Output "Release key initialized at $directory. Password is protected for this Windows account; use backup_signing.ps1 for a portable backup."
