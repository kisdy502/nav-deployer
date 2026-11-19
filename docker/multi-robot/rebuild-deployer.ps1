# One-shot rebuild on this host: git pull -> rebuild deployer image -> recreate slots.
# Usage:
#   powershell -ExecutionPolicy Bypass -File rebuild-deployer.ps1 -Machine 205            # all slots of this machine
#   powershell -ExecutionPolicy Bypass -File rebuild-deployer.ps1 -Machine 205 -Slot A,B  # only slots A and B
#   powershell -ExecutionPolicy Bypass -File rebuild-deployer.ps1 -Machine 205 -NoPull    # skip git pull
# Slot list comes from docker/multi-robot/machines/<Machine>.env (SLOTS=A,B,...)
# First deployment of a slot: use deploy-slot.ps1 instead.
# NOTE: keep this file ASCII-only. PowerShell 5.1 reads BOM-less files as ANSI/GBK and chokes on CJK.
param(
    [Parameter(Mandatory = $true)]
    [string]$Machine,
    [string[]]$Slot,
    [switch]$NoPull
)
$ErrorActionPreference = "Stop"
$REPO = "D:\workspace\nav-deployer"
$PROJECT = "nav-deployer"
$ENVFILE = "$REPO\docker\multi-robot\machines\$Machine.env"

if (-not (Test-Path $ENVFILE)) { throw "machine env missing: $ENVFILE" }
$machineEnv = @{}
Get-Content $ENVFILE | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z0-9_]+)\s*=\s*(.*)$') { $machineEnv[$Matches[1]] = $Matches[2].Trim() }
}
if (-not $Slot) {
    if (-not $machineEnv.ContainsKey('SLOTS') -or -not $machineEnv['SLOTS']) { throw "SLOTS not defined in machines\$Machine.env" }
    $Slot = $machineEnv['SLOTS'].Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
}

function Slot-Cfg([string]$s) {
    return @{
        A = @{ File = "docker-compose.slot-a.yml"; Service = "app-slot-a"; Container = "nav-deployer-a"; Frontend = 5173; Backend = 8083; Legacy = "app" }
        B = @{ File = "docker-compose.slot-b.yml"; Service = "app-slot-b"; Container = "nav-deployer-b"; Frontend = 5174; Backend = 8183; Legacy = "agv003" }
        C = @{ File = "docker-compose.slot-c.yml"; Service = "app-slot-c"; Container = "nav-deployer-c"; Frontend = 5175; Backend = 8184; Legacy = "agv004" }
        D = @{ File = "docker-compose.slot-d.yml"; Service = "app-slot-d"; Container = "nav-deployer-d"; Frontend = 5176; Backend = 8185; Legacy = "agv005" }
    }[$s]
}

Set-Location $REPO

if (-not $NoPull) {
    Write-Host "== 1/3 git pull ==" -ForegroundColor Cyan
    git pull --ff-only
    if ($LASTEXITCODE -ne 0) { throw "git pull failed (commit/stash local changes on this machine first)" }
}

Write-Host "== 2/3 build image (nav-deployer:v0.0.7) ==" -ForegroundColor Cyan
docker compose --env-file $ENVFILE -f docker-compose.slot-a.yml build app-slot-a
if ($LASTEXITCODE -ne 0) { throw "docker compose build failed" }

Write-Host "== 3/3 recreate slots: $($Slot -join ',') ==" -ForegroundColor Cyan
foreach ($s in $Slot) {
    $cfg = Slot-Cfg $s
    if (-not $cfg -or -not (Test-Path $cfg.File)) { Write-Host "  skip slot $s (unknown or missing $($cfg.File))" -ForegroundColor Yellow; continue }

    if ($cfg.Legacy) {
        $legacyCtr = "nav-deployer-$($cfg.Legacy)"
        if (docker inspect $legacyCtr 2>$null) {
            docker rm -f $legacyCtr | Out-Null
            Write-Host "  slot $s : removed legacy container $legacyCtr" -ForegroundColor Yellow
        }
    }
    $ctr = $cfg.Container
    if (docker inspect $ctr 2>$null) {
        $proj = docker inspect $ctr --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>$null
        if (-not $proj) {
            Write-Host "  slot $s : takeover legacy unmanaged container $ctr" -ForegroundColor Yellow
            docker rm -f $ctr | Out-Null
        }
    }
    Write-Host "  slot $s : up $ctr (frontend $($cfg.Frontend) / backend $($cfg.Backend))" -ForegroundColor Cyan
    docker compose --env-file $ENVFILE -f $cfg.File -p $PROJECT up -d $cfg.Service
    if ($LASTEXITCODE -ne 0) { throw "compose up failed for slot $s" }
}

Write-Host ""
Write-Host "Done. Running deployer containers:" -ForegroundColor Green
docker ps --filter "name=nav-deployer" --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"
Write-Host ""
Write-Host "Logs:   docker logs -f nav-deployer-a / -b / -c / -d"
Write-Host "Rollback: cd $REPO ; git checkout <old-commit> ; rerun with -NoPull"
