# Deploy ONE robot slot on this host (sim stack + database + deployer instance).
# First deployment per slot only; for code updates use rebuild-deployer.ps1.
# Usage:
#   powershell -ExecutionPolicy Bypass -File deploy-slot.ps1 -Slot A -Machine 206
#   powershell -ExecutionPolicy Bypass -File deploy-slot.ps1 -Slot B -Machine 205   # auto-migrates legacy agv003 dirs
# Slot map (IDENTICAL on every machine, frontend always starts at 5173):
#   A = 1st robot  frontend 5173 backend 8083  ws 9090 http 8080  sim dir D:\workspace\simulated_chassis
#   B = 2nd robot  frontend 5174 backend 8183  ws 9091 http 8081  sim dir D:\workspace\chassis-slot-b
#   C = 3rd robot  frontend 5175 backend 8184  ws 9092 http 8082  sim dir D:\workspace\chassis-slot-c
#   D = 4th robot  frontend 5176 backend 8185  ws 9093 http 8084  sim dir D:\workspace\chassis-slot-d
# RMS identity (globally unique SN) is read from docker/multi-robot/machines/<Machine>.env
# NOTE: keep this file ASCII-only. PowerShell 5.1 reads BOM-less files as ANSI/GBK and chokes on CJK.
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('A', 'B', 'C', 'D')]
    [string]$Slot,
    [Parameter(Mandatory = $true)]
    [string]$Machine
)
$ErrorActionPreference = "Stop"
$REPO = "D:\workspace\nav-deployer"
$SRC = "D:\workspace\simulated_chassis"
$PROJECT = "nav-deployer"
$ENVFILE = "$REPO\docker\multi-robot\machines\$Machine.env"

$slotCfg = @{
    A = @{ File = "docker-compose.slot-a.yml"; Service = "app-slot-a"; Container = "nav-deployer-a"; Db = "nav_deployer"; Frontend = 5173; Backend = 8083; Ws = 9090; Legacy = "app" }
    B = @{ File = "docker-compose.slot-b.yml"; Service = "app-slot-b"; Container = "nav-deployer-b"; Db = "nav_deployer_b"; Frontend = 5174; Backend = 8183; Ws = 9091; Legacy = "agv003" }
    C = @{ File = "docker-compose.slot-c.yml"; Service = "app-slot-c"; Container = "nav-deployer-c"; Db = "nav_deployer_c"; Frontend = 5175; Backend = 8184; Ws = 9092; Legacy = "agv004" }
    D = @{ File = "docker-compose.slot-d.yml"; Service = "app-slot-d"; Container = "nav-deployer-d"; Db = "nav_deployer_d"; Frontend = 5176; Backend = 8185; Ws = 9093; Legacy = "agv005" }
}[$Slot]

if (-not (Test-Path $ENVFILE)) { throw "machine env missing: $ENVFILE (create it in repo, see README.md)" }
$machineEnv = @{}
Get-Content $ENVFILE | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z0-9_]+)\s*=\s*(.*)$') { $machineEnv[$Matches[1]] = $Matches[2].Trim() }
}
$snKey = "RMS_SN_$Slot"
if (-not $machineEnv.ContainsKey($snKey) -or -not $machineEnv[$snKey]) {
    throw "$snKey not defined in machines\$Machine.env (RMS identity must be globally unique)"
}

Set-Location $REPO
Write-Host "== Deploy slot $Slot on machine $Machine (sn=$($machineEnv[$snKey])) ==" -ForegroundColor Cyan

if ($Slot -eq 'A') {
    if (-not (Test-Path "$SRC\docker-compose.yml")) {
        throw "sim stack missing: $SRC (clone/copy simulated_chassis there first, see README one-time setup)"
    }
    Write-Host "== 1/4 start sim stack (simulated_chassis, first start takes 1-2 min) ==" -ForegroundColor Cyan
    $CHASSIS = $SRC
    Push-Location $CHASSIS
    docker compose up -d
    Pop-Location
} else {
    $CHASSIS = "D:\workspace\chassis-slot-$($Slot.ToLower())"
    $legacyDir = "D:\workspace\chassis-$($slotCfg.Legacy)"
    if ((Test-Path $legacyDir) -and -not (Test-Path $CHASSIS)) {
        Write-Host "== legacy migration: $legacyDir -> $CHASSIS (down old project + rename, maps preserved) ==" -ForegroundColor Yellow
        docker compose -p "chassis-$($slotCfg.Legacy)" down
        if ($LASTEXITCODE -ne 0) { throw "legacy sim stack down failed" }
        Rename-Item $legacyDir $CHASSIS
    }
    Write-Host "== 1/4 prepare chassis dir $CHASSIS ==" -ForegroundColor Cyan
    New-Item -ItemType Directory -Path $CHASSIS -Force | Out-Null
    $tpl = "docker\multi-robot\chassis-slot-$($Slot.ToLower())"
    Copy-Item "$REPO\$tpl\docker-compose.yml" "$CHASSIS\" -Force
    Copy-Item "$REPO\$tpl\.env" "$CHASSIS\" -Force
    if (-not (Test-Path "$CHASSIS\docker")) { Copy-Item -Recurse "$SRC\docker" "$CHASSIS\docker" }
    if (-not (Test-Path "$CHASSIS\maps")) { Copy-Item -Recurse "$SRC\maps" "$CHASSIS\maps" }
    New-Item -ItemType Directory -Path "$CHASSIS\runtime\simulation\ros", "$CHASSIS\runtime\bridge\ros", "$CHASSIS\runtime\bridge\process" -Force | Out-Null
    $agvKey = "AGV_ID_$Slot"
    if ($machineEnv.ContainsKey($agvKey) -and $machineEnv[$agvKey]) {
        (Get-Content "$CHASSIS\.env") -replace '^AGV_ID=.*', "AGV_ID=$($machineEnv[$agvKey])" | Set-Content "$CHASSIS\.env"
        Write-Host "  sim AGV_ID overridden: $($machineEnv[$agvKey])"
    }
    Push-Location $CHASSIS
    docker compose up -d
    Pop-Location
}

Write-Host "== 2/4 ensure database $($slotCfg.Db) ==" -ForegroundColor Cyan
$exists = docker exec robot-postgres psql -U robot_user -d postgres -t -c "SELECT 1 FROM pg_database WHERE datname='$($slotCfg.Db)'"
if ("$exists" -notmatch "1") {
    docker exec robot-postgres psql -U robot_user -d postgres -c "CREATE DATABASE $($slotCfg.Db) OWNER robot_user"
    Write-Host "  created $($slotCfg.Db)"
} else {
    Write-Host "  exists, skip"
}

Write-Host "== 3/4 wait for bridge websocket on port $($slotCfg.Ws) ==" -ForegroundColor Cyan
$ready = $false
for ($i = 0; $i -lt 36; $i++) {
    Start-Sleep -Seconds 5
    $conn = Test-NetConnection -ComputerName 127.0.0.1 -Port $slotCfg.Ws -WarningAction SilentlyContinue
    if ($conn.TcpTestSucceeded) { $ready = $true; break }
    Write-Host "  waiting... ($($i*5+5)s)"
}
if (-not $ready) {
    Write-Host "  bridge $($slotCfg.Ws) not ready within 3 min, check: docker compose -f $CHASSIS\docker-compose.yml logs agv-bridge" -ForegroundColor Yellow
} else {
    Write-Host "  bridge ready"
}

Write-Host "== 4/4 start deployer $($slotCfg.Container) ==" -ForegroundColor Cyan
if ($slotCfg.Legacy) {
    $legacyCtr = "nav-deployer-$($slotCfg.Legacy)"
    if (docker inspect $legacyCtr 2>$null) {
        docker rm -f $legacyCtr | Out-Null
        Write-Host "  removed legacy container $legacyCtr" -ForegroundColor Yellow
    }
}
$ctr = $slotCfg.Container
if (docker inspect $ctr 2>$null) {
    $proj = docker inspect $ctr --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>$null
    if (-not $proj) {
        Write-Host "  takeover legacy unmanaged container $ctr" -ForegroundColor Yellow
        docker rm -f $ctr | Out-Null
    }
}
docker compose --env-file $ENVFILE -f $slotCfg.File -p $PROJECT up -d $slotCfg.Service
if ($LASTEXITCODE -ne 0) { throw "compose up failed for slot $Slot" }

Write-Host ""
Write-Host "Slot $Slot deployed. Checklist:" -ForegroundColor Green
Write-Host "  1. Frontend http://localhost:$($slotCfg.Frontend)  Backend http://localhost:$($slotCfg.Backend)"
Write-Host "  2. RMS should show robot sn=$($machineEnv[$snKey])"
Write-Host "  3. New DB is empty: import + publish the SAME map package via the frontend"
Write-Host "  4. Map import 409 (map exists on robot): cd $CHASSIS ; docker compose down ; del /q maps\* ; docker compose up -d ; re-import"
