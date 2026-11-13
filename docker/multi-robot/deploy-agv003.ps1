# AGV003 多车部署一键脚本（在 205 机器上以 PowerShell 运行）
# 前置：仓库已 git pull 到 D:\workspace\nav-deployer；现有 AGV002 栈（simulated_chassis + nav-deployer-app）保持运行不动
# 用法：powershell -ExecutionPolicy Bypass -File D:\workspace\nav-deployer\docker\multi-robot\deploy-agv003.ps1
# 幂等：重复运行安全（建库/复制/启动都是"没有才做"）
$ErrorActionPreference = "Stop"

$REPO = "D:\workspace\nav-deployer"
$SRC = "D:\workspace\simulated_chassis"
$CHASSIS = "D:\workspace\chassis-agv003"

Write-Host "== 1/5 准备 AGV003 chassis 目录（compose/.env 来自仓库，支撑文件来自现有 AGV002 栈）==" -ForegroundColor Cyan
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
Write-Host "  就绪"

Write-Host "== 2/5 创建独立数据库 nav_deployer_003 ==" -ForegroundColor Cyan
$exists = docker exec robot-postgres psql -U robot_user -d postgres -t -c "SELECT 1 FROM pg_database WHERE datname='nav_deployer_003'"
if ("$exists" -match "1") {
    Write-Host "  库已存在，跳过"
} else {
    docker exec robot-postgres psql -U robot_user -d postgres -c "CREATE DATABASE nav_deployer_003 OWNER robot_user"
    Write-Host "  已创建 nav_deployer_003"
}

Write-Host "== 3/5 启动 AGV003 仿真栈（复用 simulated-robot/agv-bridge:wheel-3d 镜像，首次启动约 1-2 分钟）==" -ForegroundColor Cyan
Push-Location $CHASSIS
docker compose up -d
Pop-Location

Write-Host "== 4/5 等待 bridge WebSocket 就绪（宿主 9091）==" -ForegroundColor Cyan
$ready = $false
for ($i = 0; $i -lt 36; $i++) {
    Start-Sleep -Seconds 5
    $conn = Test-NetConnection -ComputerName 127.0.0.1 -Port 9091 -WarningAction SilentlyContinue
    if ($conn.TcpTestSucceeded) { $ready = $true; break }
    Write-Host "  等待中... ($($i*5+5)s)"
}
if (-not $ready) {
    Write-Host "  bridge 9091 未在 3 分钟内就绪，请查日志: docker compose -f $CHASSIS\docker-compose.yml logs agv-bridge" -ForegroundColor Yellow
} else {
    Write-Host "  bridge 已就绪"
}

Write-Host "== 5/5 启动 AGV003 上位机实例（8183 后端 / 5174 前端）==" -ForegroundColor Cyan
Push-Location $REPO
docker compose -f docker-compose.agv003.yml -p nav-deployer up -d
Pop-Location

Write-Host ""
Write-Host "部署完成。验收清单：" -ForegroundColor Green
Write-Host "  1. docker ps 应看到新容器：chassis-agv003-robot-simulation-1 / chassis-agv003-agv-bridge-1 / nav-deployer-agv003"
Write-Host "  2. 前端 AGV002: http://localhost:5173   AGV003: http://localhost:5174"
Write-Host "  3. 两实例连接同一 RMS（zenoh），RMS 页面应出现 JY历飞雨 与 JYTREE003 两台车"
Write-Host "  4. AGV003 新库无地图：在 5174 前端导入与 AGV002 相同的地图包并发布，才能接受调度"
Write-Host "  5. 资源观察：docker stats —— 两车仿真约 16 核会接近满载，属预期（机械臂栈保留）"
