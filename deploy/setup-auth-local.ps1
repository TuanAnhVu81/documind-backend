$ErrorActionPreference = 'Stop'
$envPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    throw 'Create the local .env database settings first (see README).'
}
$content = [System.IO.File]::ReadAllText($envPath)
if ($content -notmatch '(?m)^JWT_SECRET=\S+') {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $line = 'JWT_SECRET=' + [Convert]::ToBase64String($bytes)
    if ($content -match '(?m)^JWT_SECRET=.*$') {
        $content = [regex]::Replace($content, '(?m)^JWT_SECRET=.*$', $line)
    } else { $content = $content.TrimEnd() + "`r`n" + $line + "`r`n" }
}
if ($content -notmatch '(?m)^AUTH_ALLOWED_ORIGINS=\S+') {
    $line = 'AUTH_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:8080'
    if ($content -match '(?m)^AUTH_ALLOWED_ORIGINS=.*$') {
        $content = [regex]::Replace($content, '(?m)^AUTH_ALLOWED_ORIGINS=.*$', $line)
    } else { $content = $content.TrimEnd() + "`r`n" + $line + "`r`n" }
}
[System.IO.File]::WriteAllText($envPath, $content, (New-Object System.Text.UTF8Encoding($false)))
Write-Output 'Local auth configuration is ready in the ignored .env; no secret was printed.'
