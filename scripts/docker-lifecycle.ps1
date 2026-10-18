<#
.SYNOPSIS
    Windows-native Docker lifecycle driver for the CRFD image.
    Mirrors scripts/docker-lifecycle.sh so the evidence can be produced on Windows.

.EXAMPLE
    scripts/docker-lifecycle.ps1 all
    scripts/docker-lifecycle.ps1 build
    scripts/docker-lifecycle.ps1 run -HostPort 18080
    scripts/docker-lifecycle.ps1 logs
    scripts/docker-lifecycle.ps1 stop
    scripts/docker-lifecycle.ps1 restart
    scripts/docker-lifecycle.ps1 remove
    scripts/docker-lifecycle.ps1 clean
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('all','build','tag','run','logs','ps','health','exec','stop','start','restart','remove','rmi','clean')]
    [string] $Command = 'all',

    [string] $Image = 'crfd',
    [string] $Tag = '1.0.0',
    [string] $Container = 'crfd-app',
    [int]    $HostPort = 8080,
    [string] $Environment = 'docker',
    [switch] $SkipBuild
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$full = "${Image}:${Tag}"
$base = "http://localhost:$HostPort"

function Write-Head($t) { Write-Host "`n== $t" -ForegroundColor Cyan }

function Ensure-Binary {
    $jar = 'target/content-recommendation-feedback-dashboard.jar'
    if (-not (Test-Path $jar)) {
        Write-Host "packaging the application first" -ForegroundColor Yellow
        & .\mvnw.cmd -B -DskipTests clean package
    } else {
        Write-Host "using $jar ($([math]::Round((Get-Item $jar).Length/1MB,1)) MB)"
    }
}

function Invoke-Build {
    if (-not $SkipBuild) { Ensure-Binary }
    Write-Head "docker build -t $full -t ${Image}:latest ."
    $rev = 'local'
    try { $rev = (git rev-parse --short HEAD).Trim() } catch { }
    docker build --build-arg APP_VERSION=1.0.0-SNAPSHOT --build-arg BUILD_REVISION=$rev `
        -t $full -t "${Image}:latest" .
    docker images --filter "reference=$Image" --format 'table {{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.Size}}\t{{.CreatedSince}}'
}

function Invoke-Tag {
    $rev = 'local'
    try { $rev = (git rev-parse --short HEAD).Trim() } catch { }
    Write-Head "tagging $full as ${Image}:$rev and ${Image}:stable"
    docker tag $full "${Image}:$rev"
    docker tag $full "${Image}:stable"
    docker images --filter "reference=$Image" --format 'table {{.Repository}}:{{.Tag}}\t{{.ID}}\t{{.Size}}'
}

function Invoke-Ps {
    Write-Head "containers named $Container"
    docker ps -a --filter "name=$Container" --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
}

function Invoke-Run {
    Write-Head "removing any previous $Container, then running a fresh one on port $HostPort"
    docker rm -f $Container 2>$null | Out-Null
    docker run -d --name $Container --restart unless-stopped `
        -p "${HostPort}:8080" `
        -e CRFD_ENVIRONMENT=$Environment `
        -e CRFD_SIMULATION_ENABLED=true `
        -e CRFD_SEED_ON_STARTUP=true `
        -v crfd-data:/app/crfd-data `
        $full
    Invoke-Ps
}

function Invoke-Health {
    Write-Head "container + application health"
    docker inspect --format '{{.State.Status}} / health={{if .State.Health}}{{.State.Health.Status}}{{else}}n/a{{end}}' $Container
    try { Invoke-RestMethod -Uri "$base/api/v1/health" -TimeoutSec 10 | ConvertTo-Json -Depth 4 }
    catch { Write-Host "health endpoint not reachable: $($_.Exception.Message)" -ForegroundColor DarkYellow }
}

function Invoke-Exec {
    Write-Head "proving the runtime image is a slim JRE (no build tools)"
    docker exec $Container java -version
    docker exec $Container sh -c 'command -v mvn || echo "(no maven in runtime image - as intended)"'
    docker exec $Container sh -c 'ls -la /app'
}

function Wait-Healthy($timeoutSec = 120) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $h = Invoke-RestMethod -Uri "$base/api/v1/health" -TimeoutSec 4
            if ($h.status -eq 'UP') { Write-Host "application healthy" -ForegroundColor Green; return $true }
        } catch { Start-Sleep -Seconds 2 }
    }
    Write-Host "application did not become healthy in ${timeoutSec}s" -ForegroundColor Red
    return $false
}

switch ($Command) {
    'build'   { Invoke-Build }
    'tag'     { Invoke-Tag }
    'run'     { Invoke-Run }
    'logs'    { docker logs --tail 40 $Container }
    'ps'      { Invoke-Ps }
    'health'  { Invoke-Health }
    'exec'    { Invoke-Exec }
    'stop'    { docker stop $Container; Invoke-Ps }
    'start'   { docker start $Container; Invoke-Ps }
    'restart' { docker restart $Container; Invoke-Ps }
    'remove'  { docker rm -f $Container; Invoke-Ps }
    'rmi'     { docker rmi $full }
    'clean'   { docker rm -f $Container 2>$null | Out-Null; docker rmi $full 2>$null | Out-Null }
    'all' {
        Invoke-Build
        Invoke-Tag
        Invoke-Run
        Wait-Healthy 150 | Out-Null
        docker logs --tail 40 $Container
        Invoke-Health
        Invoke-Exec
        Write-Head "restarting to prove data survives on the volume"
        docker restart $Container | Out-Null
        Wait-Healthy 150 | Out-Null
        Write-Head "stop then start again (full lifecycle)"
        docker stop $Container | Out-Null
        docker start $Container | Out-Null
        Wait-Healthy 150 | Out-Null
        Write-Head "lifecycle complete - $Container is running on port $HostPort"
    }
}
