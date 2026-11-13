# AGV003 multi-robot deployment one-shot script (run on the 205 machine, Windows PowerShell 5.1)
# Pre-req: repo pulled at D:\workspace\nav-deployer; existing AGV002 stack stays running untouched.
# Usage:  powershell -ExecutionPolicy Bypass -File D:\workspace\nav-deployer\docker\multi-robot\deploy-agv003.ps1
# Idempotent: safe to re-run (create/copy/start only when missing).
# NOTE: keep this file ASCII-only. PowerShell 5.1 reads BOM-less files as ANSI/GBK and chokes on CJK.
$ErrorActionPreference = "Stop"

$REPO = "D:\workspace\nav-deployer"
$SRC = "D:\workspace\simulated_chassis"
$CHASSIS = "D:\workspace\chassis-agv003"

Write-Host "== 1/5 Prepare AGV003 chassis dir (compose/.env from repo, support files from AGV002 stack) ==" -ForegroundColor Cyan
New-Item -ItemType Directory -Path $CHASSIS -Force | Out-Null
Copy-Item "$REPO\docker\multi-robot\chassis-agv003\docker-compose.yml" "$CHASSIS\" -Force
Copy-Item "$REPO\docker\multi-robot\chassis-agv003\.env" "$CHASSIS\" -Force
if (-not (Test-Path "$CHASSIS\docker")) {
    Copy-Item -Recurse "$SRC\docker" "$CHASSIS\docker"
}
if (-not (Test-Path "$CHASSIS\maps")) {
    Copy-Item -Recurse "$SRC\maps" "$CHASSIS\maps"
}
New-Item -ItemType Directory -Path "$CHASSIS\runtime\simulation\ros", "$CHASSIS\runtime\bridge\ros", "$CHASSIS\runtime\bridge\process" -Force | Out-Null
Write-Host "  done"

Write-Host "== 2/5 Create database nav_deployer_003 ==" -ForegroundColor Cyan
$exists = docker exec robot-postgres psql -U robot_user -d postgres -t -c "SELECT 1 FROM pg_database WHERE datname='nav_deployer_003'"
if ("$exists" -match "1") {
    Write-Host "  already exists, skip"
} else {
    docker exec robot-postgres psql -U robot_user -d postgres -c "CREATE DATABASE nav_deployer_003 OWNER robot_user"
    Write-Host "  created nav_deployer_003"
}

Write-Host "== 3/5 Start AGV003 simulation stack (reuses simulated-robot/agv-bridge:wheel-3d, first start takes 1-2 min) ==" -ForegroundColor Cyan
Push-Location $CHASSIS
docker compose up -d
Pop-Location

Write-Host "== 4/5 Wait for bridge websocket on host port 9091 ==" -ForegroundColor Cyan
$ready = $false
for ($i = 0; $i -lt 36; $i++) {
    Start-Sleep -Seconds 5
    $conn = Test-NetConnection -ComputerName 127.0.0.1 -Port 9091 -WarningAction SilentlyContinue
    if ($conn.TcpTestSucceeded) { $ready = $true; break }
    Write-Host "  waiting... ($($i*5+5)s)"
}
if (-not $ready) {
    Write-Host "  bridge 9091 not ready within 3 min, check: docker compose -f $CHASSIS\docker-compose.yml logs agv-bridge" -ForegroundColor Yellow
} else {
    Write-Host "  bridge ready"
}

Write-Host "== 5/5 Start AGV003 deployer instance (backend 8183 / frontend 5174) ==" -ForegroundColor Cyan
Push-Location $REPO
docker compose -f docker-compose.agv003.yml -p nav-deployer up -d
Pop-Location

Write-Host ""
Write-Host "Deploy finished. Checklist:" -ForegroundColor Green
Write-Host "  1. docker ps: chassis-agv003-robot-simulation-1 / chassis-agv003-agv-bridge-1 / nav-deployer-agv003"
Write-Host "  2. Frontend  AGV002: http://localhost:5173   AGV003: http://localhost:5174"
Write-Host "  3. RMS should show two robots (JY-LiFeiYu and JYTREE003)"
Write-Host "  4. AGV003 DB is empty: import the SAME map package via 5174 frontend and publish it"
Write-Host "  5. Watch resources: docker stats (2 simulated robots will nearly saturate 16 cores)"
