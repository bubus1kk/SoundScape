param(
    [Parameter(Mandatory)]
    [string]$Workdir,
    [string]$Supabase = 'supabase',
    [string]$Docker = 'docker'
)

$ErrorActionPreference = 'Stop'
$Workdir = (Resolve-Path -LiteralPath $Workdir).Path
$config = Get-Content -Raw -LiteralPath (Join-Path $Workdir 'supabase/config.toml')
$project = [regex]::Match($config, '(?m)^project_id\s*=\s*"(ss-be02-[a-z0-9-]+)"\s*$').Groups[1].Value
if (-not $project) {
    throw 'Specify the workdir of an isolated ss-be02-* project.'
}

$status = @{}
& $Supabase --workdir $Workdir status -o env 2>$null | ForEach-Object {
    if ($_ -match '^([A-Z_]+)=(.*)$') { $status[$Matches[1]] = $Matches[2].Trim('"') }
}
$localPort = [regex]::Match([string]$status.API_URL, '^http://127\.0\.0\.1:(\d{4,5})$').Groups[1].Value
if ($LASTEXITCODE -or -not $localPort) { throw 'The isolated Supabase stack is unavailable.' }
$gateway = "supabase_kong_$project"
if (-not ((& $Docker port $gateway 8000/tcp) -match ":$localPort$")) {
    throw 'The local API port does not belong to the selected gateway.'
}

$fixture = @{
    STAB_LOCAL_PORT = '8000'
    STAB_LOCAL_ANON_KEY = $status.ANON_KEY
    STAB_LOCAL_SERVICE_KEY = $status.SERVICE_ROLE_KEY
    STAB_LOCAL_SECRET_KEY = $status.SECRET_KEY
    BE02_LOCAL_PORT = '8000'
    BE02_LOCAL_MAIL_HOST = 'inbucket'
    BE02_LOCAL_MAIL_PORT = '8025'
    BE02_LOCAL_ANON_KEY = $status.ANON_KEY
    BE02_LOCAL_SERVICE_KEY = $status.SERVICE_ROLE_KEY
    BE02_LOCAL_JWT_SECRET = $status.JWT_SECRET
}
foreach ($name in $fixture.Keys) {
    if (-not $fixture[$name]) { throw "Local fixture value is missing for $name." }
}
$extraEnv = @(
    'SUPABASE_URL', 'SUPABASE_PUBLISHABLE_KEY', 'SUPABASE_PUBLISHABLE_KEYS',
    'SUPABASE_SECRET_KEY', 'SUPABASE_SECRET_KEYS', 'SUPABASE_ANON_KEY', 'SUPABASE_SERVICE_ROLE_KEY',
    'AUDIUS_API_KEY', 'AUDIUS_CONTENT_HOSTS', 'AUDIUS_OPERATION_PARALLEL', 'AUDIUS_OPERATION_UPDATES',
    'GIGACHAT_AUTH_KEY', 'GIGACHAT_SCOPE', 'GIGACHAT_MODEL'
)
$allowEnv = (@($fixture.Keys) + $extraEnv) -join ','
$container = 'ss-be02-test-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$supabaseSource = Join-Path (Split-Path -Parent $PSScriptRoot) 'supabase'

# The integration fixtures exercise the database limiter while intercepting provider requests.
$seedSql = 'INSERT INTO internal.audius_budget(requests_per_second,requests_per_month) VALUES (100,1000000) ON CONFLICT DO NOTHING'
& $Docker exec "supabase_db_$project" psql -X -U postgres -d postgres -v ON_ERROR_STOP=1 -c $seedSql
if ($LASTEXITCODE) { exit $LASTEXITCODE }

$previous = @{}
$exported = foreach ($name in $fixture.Keys) { '--env'; $name }
$created = $false
try {
    foreach ($name in $fixture.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $fixture[$name], 'Process')
    }
    $testArgs = @('test', '--quiet', '--allow-net=127.0.0.1:8000,inbucket:8025',
        "--allow-env=$allowEnv", '/tmp/tests/integration')
    & $Docker create --rm --name $container --network "container:$gateway" @exported denoland/deno:2.9.8 @testArgs | Out-Null
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
    $created = $true
    foreach ($directory in 'tests', 'functions') {
        & $Docker cp (Join-Path $supabaseSource $directory) "${container}:/tmp/$directory"
        if ($LASTEXITCODE) { exit $LASTEXITCODE }
    }
    & $Docker start -a $container
    exit $LASTEXITCODE
} finally {
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
    if ($created -and (& $Docker ps -a --filter "name=^/${container}$" --format '{{.Names}}') -eq $container) {
        & $Docker rm $container | Out-Null
    }
}
