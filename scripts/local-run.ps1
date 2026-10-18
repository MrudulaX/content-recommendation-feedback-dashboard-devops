<#
.SYNOPSIS
    Build (if needed) and run the CRFD application locally as a fat JAR.

.DESCRIPTION
    The MVP is a single Spring Boot JAR with an embedded Tomcat, so "running it
    locally" means exactly this: package once, then start it on a chosen port.

    Default port is 18080 because 8080 is frequently occupied on developer
    machines - override with -Port.

.EXAMPLE
    scripts/local-run.ps1
    scripts/local-run.ps1 -Port 9090 -Environment local -SeedOnStartup:$false
#>
[CmdletBinding()]
param(
    [int]    $Port = 18080,
    [string] $Environment = 'local',
    [string] $Profile = '',
    [bool]   $SeedOnStartup = $true,
    [bool]   $SimulationEnabled = $true,
    [switch] $SkipBuild,
    [switch] $NoWait
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$jar = Join-Path $root 'target/content-recommendation-feedback-dashboard.jar'

Write-Host "== CRFD local run ==" -ForegroundColor Cyan

if (-not $SkipBuild -or -not (Test-Path $jar)) {
    Write-Host "packaging (mvnw -B -DskipTests clean package)" -ForegroundColor Cyan
    & .\mvnw.cmd -B -DskipTests clean package
    if ($LASTEXITCODE -ne 0) { throw "Maven packaging failed" }
}

if (-not (Test-Path $jar)) { throw "expected artifact not found: $jar" }

$existing = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($existing) {
    Write-Warning "port $Port is already in use by PID $($existing[0].OwningProcess) - stopping the app will not help; choose another -Port"
}

$envVars = @{
    'CRFD_ENVIRONMENT'         = $Environment
    'CRFD_SEED_ON_STARTUP'     = $SeedOnStartup.ToString().ToLower()
    'CRFD_SIMULATION_ENABLED'  = $SimulationEnabled.ToString().ToLower()
    'SERVER_PORT'              = "$Port"
}

Write-Host ("starting: java -jar {0} (port {1}, env {2})" -f (Split-Path $jar -Leaf), $Port, $Environment) -ForegroundColor Green

$argList = @('-jar', $jar)
if ($Profile) { $argList += @('--spring.profiles.active=' + $Profile) }
$argList += @("--server.port=$Port")

foreach ($k in $envVars.Keys) { Set-Item -Path "Env:$k" -Value $envVars[$k] }

$proc = Start-Process -FilePath 'java' -ArgumentList $argList -NoNewWindow -PassThru

if (-not $NoWait) {
    $base = "http://localhost:$Port"
    Write-Host "waiting for $base/api/v1/health ..." -ForegroundColor Cyan
    $deadline = (Get-Date).AddSeconds(90)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-RestMethod -Uri "$base/api/v1/health" -TimeoutSec 4
            if ($r.status -eq 'UP') { $ready = $true; break }
        } catch { Start-Sleep -Milliseconds 1500 }
    }
    if ($ready) {
        Write-Host "UP  dashboard: $base/   api: $base/api/v1   h2: $base/h2-console" -ForegroundColor Green
    } else {
        Write-Warning "application did not report healthy within 90s (PID $($proc.Id))"
    }
}

Write-Host "PID $($proc.Id) - stop with: Stop-Process -Id $($proc.Id)" -ForegroundColor Yellow
