function Get-RainSigningPassword {
    param([string]$SigningDirectory)
    $secretFile = Join-Path $SigningDirectory 'password.dpapi'
    if (-not (Test-Path -LiteralPath $secretFile)) { throw 'Signing password not provisioned; run initialize_signing.ps1 on this Windows account.' }
    $secure = Get-Content -LiteralPath $secretFile -Raw | ConvertTo-SecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secure.Dispose() }
}
