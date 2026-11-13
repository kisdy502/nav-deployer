# Generic multi-robot deployment script (run on the 205 machine, Windows PowerShell 5.1).
# Usage:
#   powershell -ExecutionPolicy Bypass -File deploy-vehicle.ps1 -Id 4     # deploys AGV004
#   powershell -ExecutionPolicy Bypass -File deploy-vehicle.ps1 -Id 5     # deploys AGV005
# Prereq per vehicle in repo (docker/multi-robot/chassis-agv00N + docker-compose.agv00N.yml at repo root).
# Port conventions: AGV00N -> ws 909(N-1), http 808(N-1) or 8084 for N=5, frontend 517(2+N), backend 818(N)
# NOTE: keep this file ASCII-only. PowerShell 5.1 reads BOM-less files as ANSI/GBK and chokes on CJK.
param(
    [Parameter(Mandatory = $true)]
    [int]$Id
)
$ErrorActionPreference = "Stop"

$REPO = "D:\workspace\nav-deployer"
$SRC = "D:\workspace\simulated_chassis"
$TAG = "agv00$Id"
$CHASSIS = "D:\workspace\chassis-$TAG"
$DB = "nav_deployer_00$Id"
$FRONTEND_PORT = 5172 + $Id          # 004 -> 5175, 005 -> 5176 (003 -> 5175? no: 5172+3=5175 for 003 was 5174 via old script)
$BACKEND_PORT = 8180 + $Id           # 004 -> 8184, 005 -> 8185
$WS_PORT = 9088 + $Id                # 004 -> 9092, 005 -> 9093

Write-Host "== Deploy vehicle $TAG (ws=$WS_PORT frontend=$FRONTEND_PORT backend=$BACKEND_PORT db=$DB) ==" -ForegroundColor Cyan

if (-not (Test-Path "$REPO\docker\multi-robot\chassis-$TAG\docker-compose.yml")) {
    throw "repo config missing: $REPO\docker\multi-robot\chassis-$TAG"
}
if (-not (Test-Path "$REPO\docker-compose.$TAG.yml")) {
    throw "repo config missing: $REPO\docker-compose.$TAG.yml"
}

Write-Host "== 1/5 Prepare chassis dir ==" -ForegroundColor Cyan
New-Item -ItemType Directory -Path $CHASSIS -Force | Out-Null
Copy-Item "$REPO\docker\multi-robot\chassis-$TAG\docker-compose.yml" "$CHASSIS\" -Force
Copy-Item "$REPO\docker\multi-robot\chassis-$TAG\.env" "$CHASSIS\" -Force
if (-not (Test-Path "$CHASSIS\docker")) { Copy-Item -Recurse "$SRC\docker" "$CHASSIS\docker" }
if (-not (Test-Path "$CHASSIS\maps")) { Copy-Item -Recurse "$SRC\maps" "$CHASSIS\maps" }
New-Item -ItemType Directory -Path "$CHASSIS\runtime\simulation\ros", "$CHASSIS\runtime\bridge\ros", "$CHASSIS\runtime\bridge\process" -Force | Out-Null
Write-Host "  done"

Write-Host "== 2/5 Create database $DB ==" -ForegroundColor Cyan
$exists = docker exec robot-postgres psql -U robot_user -d postgres -t -c "SELECT 1 FROM pg_database WHERE datname='$DB'"
if ("$exists" -match "1") {
    Write-Host "  already exists, skip"
} else {
    docker exec robot-postgres psql -U robot_user -d postgres -c "CREATE DATABASE $DB OWNER robot_user"
    Write-Host "  created $DB"
}

Write-Host "== 3/5 Start simulation stack (first start takes 1-2 min) ==" -ForegroundColor Cyan
Push-Location $CHASSIS
docker compose up -d
Pop-Location

Write-Host "== 4/5 Wait for bridge websocket on host port $WS_PORT ==" -ForegroundColor Cyan
$ready = $false
for ($i = 0; $i -lt 36; $i++) {
    Start-Sleep -Seconds 5
    $conn = Test-NetConnection -ComputerName 127.0.0.1 -Port $WS_PORT -WarningAction SilentlyContinue
    if ($conn.TcpTestSucceeded) { $ready = $true; break }
    Write-Host "  waiting... ($($i*5+5)s)"
}
if (-not $ready) {
    Write-Host "  bridge $WS_PORT not ready within 3 min, check: docker compose -f $CHASSIS\docker-compose.yml logs agv-bridge" -ForegroundColor Yellow
} else {
    Write-Host "  bridge ready"
}

Write-Host "== 5/5 Start deployer instance ==" -ForegroundColor Cyan
Push-Location $REPO
docker compose -f "docker-compose.$TAG.yml" -p nav-deployer up -d
Pop-Location

Write-Host ""
Write-Host "Deploy finished for $TAG. Checklist:" -ForegroundColor Green
Write-Host "  1. Frontend http://localhost:$FRONTEND_PORT  Backend http://localhost:$BACKEND_PORT"
Write-Host "  2. RMS should show the new robot (distinct SN/NAME)"
Write-Host "  3. New DB is empty: import + publish the SAME map package via $FRONTEND_PORT frontend"
Write-Host "  4. If map import hits robot-side 409 (map exists): stop stack, clear $CHASSIS\maps, restart, re-import"
Write-Host "     (cd $CHASSIS ; docker compose down ; del /q maps\* ; docker compose up -d)"
Write-Host "  5. Watch resources: docker stats"
