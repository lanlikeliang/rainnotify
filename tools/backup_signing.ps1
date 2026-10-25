param([Parameter(Mandatory=$true)][string]$SigningDirectory, [Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'toolchain.ps1')
. (Join-Path $PSScriptRoot 'signing.ps1')
$tc = Initialize-RainToolchain
if (Test-Path -LiteralPath $Destination) { throw 'Destination exists; choose a new private backup filename.' }
if ([IO.Path]::GetFullPath($Destination).StartsWith($tc.Root + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Choose a backup location outside the repository.' }
$password = Read-Host 'Enter a strong password for this portable signing backup (store it in your password manager)' -AsSecureString
$repeat = Read-Host 'Repeat backup password' -AsSecureString
$p1 = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($password)
$p2 = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($repeat)
try {
    $first = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($p1)
    if ($first.Length -lt 16 -or $first -ne [Runtime.InteropServices.Marshal]::PtrToStringBSTR($p2)) { throw 'Passwords must match and contain at least 16 characters.' }
    $env:RAINNOTIFY_BACKUP_PASSWORD=$first
    $env:RAINNOTIFY_SIGNING_PASSWORD=Get-RainSigningPassword $SigningDirectory
    & (Join-Path $tc.Jdk 'bin\keytool.exe') -importkeystore -srckeystore (Join-Path $SigningDirectory 'rainnotify-release.p12') -srcstoretype PKCS12 -srcalias rainnotify-release -srcstorepass:env RAINNOTIFY_SIGNING_PASSWORD -destkeystore $Destination -deststoretype PKCS12 -deststorepass:env RAINNOTIFY_BACKUP_PASSWORD -destkeypass:env RAINNOTIFY_BACKUP_PASSWORD -noprompt
    if ($LASTEXITCODE) { throw 'Backup export failed.' }
    Write-Output 'Portable signing backup created. Keep its password separately; neither belongs in Git.'
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p1); [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p2)
    $password.Dispose(); $repeat.Dispose(); $first=$null
    Remove-Item Env:\RAINNOTIFY_BACKUP_PASSWORD,Env:\RAINNOTIFY_SIGNING_PASSWORD -ErrorAction SilentlyContinue
}
