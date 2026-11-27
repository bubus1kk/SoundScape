param(
    [Parameter(Mandatory)][ValidateSet('fresh','upgrade')][string]$Mode,
    [Parameter(Mandatory)][string]$Workdir,
    [Parameter(Mandatory)][ValidateRange(57000,59900)][int]$PortBase,
    [string]$Supabase = 'supabase',
    [string]$Docker = 'docker'
)
$ErrorActionPreference = 'Stop'
$env:SUPABASE_TELEMETRY_DISABLED = '1'
$Workdir = [IO.Path]::GetFullPath($Workdir)
if (Test-Path -LiteralPath $Workdir) { throw 'Choose a new disposable workdir.' }
$project = 'ss-be03-fix-' + $Mode + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
& $Docker version --format '{{.Server.Version}}'
if ($LASTEXITCODE) { throw 'Docker Linux Engine unavailable.' }
$ports = 20,21,22,23,24,25,26,27,28,29,83 | ForEach-Object { $PortBase + $_ }
if (Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in $ports }) {
    throw 'A required port is already occupied.'
}
$source = Join-Path (Split-Path -Parent $PSScriptRoot) 'supabase'
$target = Join-Path $Workdir 'supabase'
$targetMigrations = Join-Path $target 'migrations'
New-Item -ItemType Directory -Path $targetMigrations -Force | Out-Null
$cfg = Get-Content -Raw -LiteralPath (Join-Path $source 'config.toml')
$cfg = $cfg -replace 'project_id = "backend"', ('project_id = "' + $project + '"')
$cfg = [regex]::Replace($cfg,'(?<=\bport = )543(2\d)\b',{param($m) [string]($PortBase + [int]$m.Groups[1].Value)})
$cfg = [regex]::Replace($cfg,'(?<=\bshadow_port = )54320\b',[string]($PortBase+20))
$cfg = $cfg -replace 'inspector_port = 8083',('inspector_port = ' + ($PortBase+83))
[IO.File]::WriteAllText((Join-Path $target 'config.toml'),$cfg)
foreach ($subdir in 'functions','tests') {
    Copy-Item -LiteralPath (Join-Path $source $subdir) -Destination $target -Recurse
}
$all = @(Get-ChildItem -LiteralPath (Join-Path $source 'migrations') -Filter '*.sql' | Sort-Object Name)
if ($all.Count -lt 20) { throw 'Expected the BE-03 and BE-03-FIX migrations.' }
$base = if ($Mode -eq 'upgrade') { $all | Select-Object -First 18 } else { $all }
$base | Copy-Item -Destination $targetMigrations
if ($Mode -eq 'upgrade') {
    & $Supabase --workdir $Workdir db start *> (Join-Path $Workdir 'start.log')
} else {
    & $Supabase --workdir $Workdir start -x studio,logflare,vector,realtime,postgres-meta *> (Join-Path $Workdir 'start.log')
}
if ($LASTEXITCODE) { throw "Supabase failed to start. See the local start.log in $Workdir." }
$db = "supabase_db_$project"
function Invoke-Fixture([string]$File) {
    $path = Join-Path (Split-Path -Parent $source) "tests/fixtures/$File"
    & $Docker cp $path "${db}:/tmp/$File"
    if ($LASTEXITCODE) { throw 'Fixture copy failed.' }
    & $Docker exec $db psql -X -U postgres -d postgres -v ON_ERROR_STOP=1 -f "/tmp/$File"
    if ($LASTEXITCODE) { throw 'Upgrade fixture failed.' }
}
if ($Mode -eq 'upgrade') {
    Invoke-Fixture 'editorial_upgrade_seed.sql'
    $all | Select-Object -Skip 18 | Copy-Item -Destination $targetMigrations
    & $Supabase --workdir $Workdir migration up --local
    if ($LASTEXITCODE) { throw 'Local upgrade failed.' }
    Invoke-Fixture 'editorial_upgrade_verify.sql'
} else {
    # This suite creates its own Tokyo and London fixtures in the fresh database.
    & $Supabase --workdir $Workdir test db
    if ($LASTEXITCODE) { throw 'pgTAP failed.' }
    & (Join-Path $PSScriptRoot 'test-be02-local.ps1') -Workdir $Workdir -Stage be03 -Supabase $Supabase -Docker $Docker
    if ($LASTEXITCODE) { throw 'Containerized integration suite failed.' }
}
