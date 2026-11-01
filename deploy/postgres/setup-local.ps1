param([string]$ContainerName = 'local_postgres')
$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$envPath = Join-Path $repoPath '.env'
if (Test-Path -LiteralPath $envPath) {
    Write-Output '.env already exists; leaving credentials and databases unchanged.'
    exit 0
}
$containerEnvJson = & docker inspect --format '{{json .Config.Env}}' $ContainerName
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect PostgreSQL container.' }
$containerVars = @{}
foreach ($entry in ($containerEnvJson | ConvertFrom-Json)) {
    $pair = $entry.Split('=', 2)
    $containerVars[$pair[0]] = $pair[1]
}
$adminUser = $containerVars['POSTGRES_USER']
if (-not $adminUser) { $adminUser = 'postgres' }
$adminPassword = $containerVars['POSTGRES_PASSWORD']
if (-not $adminPassword) { throw 'POSTGRES_PASSWORD is not available; configure .env manually.' }
$exists = & docker exec $ContainerName psql -U $adminUser -d postgres -At -c "SELECT (SELECT count(*) FROM pg_roles WHERE rolname='documind') + (SELECT count(*) FROM pg_database WHERE datname IN ('documind','documind_phase1_test'));"
if ($LASTEXITCODE -ne 0) { throw 'Cannot check local database names.' }
if ($exists.Trim() -ne '0') {
    throw 'DocuMind role/database already exists. Refusing to change existing credentials; configure .env manually.'
}
$randomBytes = New-Object byte[] 32
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $random.GetBytes($randomBytes) } finally { $random.Dispose() }
$runtimePassword = [BitConverter]::ToString($randomBytes).Replace('-', '').ToLowerInvariant()
$sql = "CREATE ROLE documind LOGIN PASSWORD '$runtimePassword';`nCREATE DATABASE documind OWNER documind;`nCREATE DATABASE documind_phase1_test OWNER documind;"
# SQL is piped over stdin, never passed on the command line or printed.
$sql | & docker exec -i $ContainerName psql -v ON_ERROR_STOP=1 -U $adminUser -d postgres
if ($LASTEXITCODE -ne 0) { throw 'Database initialization failed. Check created objects before retrying.' }
function Escape-Property([string]$Value) {
    return $Value.Replace('\', '\\').Replace("`r", '\r').Replace("`n", '\n').Replace(' ', '\ ')
}
$escapedAdminPassword = Escape-Property $adminPassword
$envLines = @(
    '# Generated local credentials. Never commit this file.'
    'DB_URL=jdbc:postgresql://localhost:5432/documind'
    'DB_USERNAME=documind'
    "DB_PASSWORD=$runtimePassword"
    "DB_MIGRATION_USERNAME=$adminUser"
    "DB_MIGRATION_PASSWORD=$escapedAdminPassword"
    'TEST_DB_URL=jdbc:postgresql://localhost:5432/documind_phase1_test'
    'TEST_DB_USERNAME=documind'
    "TEST_DB_PASSWORD=$runtimePassword"
    "TEST_DB_MIGRATION_USERNAME=$adminUser"
    "TEST_DB_MIGRATION_PASSWORD=$escapedAdminPassword"
    'SERVER_PORT=8080'
    "POSTGRES_PASSWORD=$runtimePassword"
    'DOCUMIND_POSTGRES_PORT=5433'
)
[System.IO.File]::WriteAllLines($envPath, $envLines, (New-Object System.Text.UTF8Encoding($false)))
Write-Output 'Created isolated documind / documind_phase1_test databases and an ignored .env. No credentials were printed.'
