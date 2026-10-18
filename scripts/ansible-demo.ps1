<#
.SYNOPSIS
    Produce the Ansible provisioning evidence on Windows, end to end, without
    needing a spare VM. Windows-native twin of scripts/ansible-demo.sh.

.DESCRIPTION
    Stands up two throwaway containers:

      crfd-node      a "clean node": SSH + a Docker engine and nothing else
                     (infra/ansible/node/Dockerfile)
      crfd-ansible   a pinned Ansible controller
                     (infra/ansible/Dockerfile.controller)

    then drives the real playbooks at it and captures the logs the report needs:

      ansible-01-provision-first-run.log    brings the node up
      ansible-02-idempotency-second-run.log changed=0 on a repeat run
      ansible-03-deploy-release.log         release 0.1.0 -> 1.0.0
      ansible-04-healthcheck.log            health verification
      ansible-05-rollback.log               recovery to 0.1.0

    The application image is copied into the node as a tar rather than piped
    through PowerShell, because binary pipelines can corrupt the stream.

.EXAMPLE
    scripts/ansible-demo.ps1
    scripts/ansible-demo.ps1 -EvidenceDir docs/evidence
#>
[CmdletBinding()]
param(
    [string] $EvidenceDir = 'docs/evidence',
    [string] $AppImage = 'crfd:1.0.0',
    [string] $PreviousTag = '0.1.0',
    [switch] $SkipImageBuild,
    [switch] $KeepRunning
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$Node = 'crfd-node'
$Controller = 'crfd-ansible'
$Network = 'crfd-demo-net'
$failures = 0

function Write-Head($t) { Write-Host "`n== $t" -ForegroundColor Cyan }
function Write-Warn2($t) { Write-Host "!! $t" -ForegroundColor Yellow }
function Write-Die($t) { Write-Host "!! $t" -ForegroundColor Red; exit 1 }

# ------------------------------------------------------------------ preconditions
docker info 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Die "the Docker daemon is not reachable" }

New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null

if (-not $SkipImageBuild) {
    $has = docker images --format '{{.Repository}}:{{.Tag}}' | Select-String -SimpleMatch $AppImage
    if (-not $has) {
        Write-Head "application image $AppImage is missing - building it"
        & .\scripts\docker-lifecycle.ps1 build
        if ($LASTEXITCODE -ne 0) { Write-Die "could not build $AppImage" }
    }
}

# ------------------------------------------------------------------- build images
Write-Head "building the disposable node image and the pinned Ansible controller"
docker build -q -f infra/ansible/node/Dockerfile -t crfd-node:latest infra/ansible/node | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Die "node image build failed" }
docker build -q -f infra/ansible/Dockerfile.controller -t crfd-ansible:latest infra/ansible | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Die "controller image build failed" }

# ------------------------------------------------------------------- clean slate
Write-Head "removing leftovers from an earlier run"
docker rm -f $Node $Controller 2>$null | Out-Null
docker network rm $Network 2>$null | Out-Null
docker network create $Network | Out-Null
Write-Host "created network $Network"

# ------------------------------------------------------------------------ node
Write-Head "starting the clean node ($Node)"
docker run -d --privileged --name $Node --network $Network --network-alias $Node crfd-node:latest | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Die "could not start the node container" }

Write-Host "waiting for the node's SSH daemon and Docker engine"
$ready = $false
for ($i = 1; $i -le 90; $i++) {
    docker exec $Node sh -c 'docker info >/dev/null 2>&1 && nc -z localhost 22' 2>$null
    if ($LASTEXITCODE -eq 0) { Write-Host "   node ready after ${i}s"; $ready = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $ready) {
    docker logs $Node
    Write-Die "the node never became ready"
}

# Load the app image into the node's own daemon. A tar file is used instead of a
# pipe because PowerShell would otherwise rewrite the binary stream.
Write-Head "loading $AppImage into the node and tagging it as crfd:$PreviousTag"
$tar = Join-Path $env:TEMP "crfd-image.tar"
docker save -o $tar $AppImage
if ($LASTEXITCODE -ne 0) { Write-Die "docker save failed" }
docker cp $tar "${Node}:/tmp/crfd-image.tar"
docker exec $Node docker load -i /tmp/crfd-image.tar | Out-Null
docker exec $Node docker tag $AppImage "crfd:$PreviousTag"
docker exec $Node docker images --format 'table {{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.Size}}'
Remove-Item $tar -Force -ErrorAction SilentlyContinue

# ------------------------------------------------------------------- controller
function Invoke-Playbook {
    param([string] $Label, [string] $Arguments)
    $log = Join-Path $EvidenceDir "$Label.log"
    Write-Head "ansible-playbook $Arguments"
    # $Arguments is passed as a single argv entry so that bash -lc receives the
    # whole command line rather than just the first word.
    #
    # demo-overrides.yml is applied as extra vars (-e @file) because extra vars
    # outrank every other source: inventory-file group vars would lose to
    # inventory/group_vars/all.yml, which is what silently pointed the demo at
    # Docker Hub. No -w here; the controller entrypoint stages the mount itself.
    docker run --rm --network $Network `
        -v "${root}/infra/ansible:/ansible" `
        -e ANSIBLE_FORCE_COLOR=0 `
        crfd-ansible:latest `
        "ansible-playbook -i inventory/hosts.demo.ini -e @inventory/demo-overrides.yml $Arguments 2>&1" `
        2>&1 | Tee-Object -FilePath $log | Out-Host
    # Out-Host matters. Without it Tee-Object's pass-through output lands in this
    # function's return value, so $rc becomes an array of log lines rather than
    # the exit code - and "@(...) -ne 0" is always truthy, which reports every
    # successful play as a failure. Out-Host still renders the output live while
    # emitting nothing downstream, so $LASTEXITCODE is the only thing returned.
    return $LASTEXITCODE
}

function Show-Recap {
    param([string] $Label)
    $log = Join-Path $EvidenceDir "$Label.log"
    if (Test-Path $log) {
        $recap = Select-String -Path $log -Pattern 'changed=\d+' -Context 0,0
        if ($recap) { $recap | Select-Object -Last 2 | ForEach-Object { Write-Host "   $($_.Line.Trim())" } }
    }
}

# ------------------------------------------------------------------- 1 first run
$rc = Invoke-Playbook 'ansible-01-provision-first-run' "playbooks/site.yml -e app_version=$PreviousTag"
if ($rc -ne 0) { Write-Warn2 "step 1 failed - see $EvidenceDir/ansible-01-provision-first-run.log"; $failures++ }
Show-Recap 'ansible-01-provision-first-run'

# --------------------------------------------------------- 2 idempotency, 2nd run
$rc = Invoke-Playbook 'ansible-02-idempotency-second-run' "playbooks/site.yml -e app_version=$PreviousTag"
if ($rc -ne 0) { Write-Warn2 "step 2 failed - see $EvidenceDir/ansible-02-idempotency-second-run.log"; $failures++ }
Show-Recap 'ansible-02-idempotency-second-run'

# ----------------------------------------------------------------- 3 new release
$rc = Invoke-Playbook 'ansible-03-deploy-release' "playbooks/deploy.yml -e app_version=1.0.0"
if ($rc -ne 0) { Write-Warn2 "step 3 failed - see $EvidenceDir/ansible-03-deploy-release.log"; $failures++ }

# ---------------------------------------------------------------------- 4 health
$rc = Invoke-Playbook 'ansible-04-healthcheck' "playbooks/healthcheck.yml"
if ($rc -ne 0) { Write-Warn2 "step 4 failed - see $EvidenceDir/ansible-04-healthcheck.log"; $failures++ }

# -------------------------------------------------------------------- 5 rollback
$rc = Invoke-Playbook 'ansible-05-rollback' "playbooks/rollback.yml"
if ($rc -ne 0) { Write-Warn2 "step 5 failed - see $EvidenceDir/ansible-05-rollback.log"; $failures++ }

# ----------------------------------------------------------------------- summary
Write-Head "evidence written to $EvidenceDir"
Get-ChildItem "$EvidenceDir/ansible-*.log" -ErrorAction SilentlyContinue |
    Select-Object Name, Length | Format-Table -AutoSize

Write-Head "container state on the node"
docker exec $Node docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'

Write-Head "rollback bookkeeping on the node"
docker exec $Node sh -c 'echo "previous-release.txt:"; cat /etc/crfd/previous-release.txt 2>/dev/null; echo "release-history.log:"; cat /etc/crfd/release-history.log 2>/dev/null'

if (-not $KeepRunning) {
    Write-Head "tearing down the demo node and controller"
    docker rm -f $Node $Controller 2>$null | Out-Null
    docker network rm $Network 2>$null | Out-Null
}

if ($failures -gt 0) {
    Write-Warn2 "$failures step(s) failed - inspect the logs above"
    exit 1
}
Write-Host "`nALL PROVISIONING STEPS PASSED" -ForegroundColor Green
