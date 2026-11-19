#!/usr/bin/env bash
# 仓库内一键 Docker 部署脚本（Windows Git Bash / Linux 通用）——本机开发入口（槽 A 单车）。
# 车队机器（205/206/...）不要用本脚本，统一走 docker/multi-robot/rebuild-deployer.ps1。
# 用法：
#   bash deploy.sh build   # 只构建应用镜像（前端 + 后端多阶段），不启动
#   bash deploy.sh up      # 构建并启动槽 A ——本机日常开发一条命令
#   bash deploy.sh start   # 不构建直接启动（用现有镜像；代码未变时秒起，适合"稳定跑"模式）
#   bash deploy.sh down    # 停止并移除容器
#   bash deploy.sh logs    # 跟随应用日志
set -euo pipefail
cd "$(dirname "$0")"

COMPOSE="docker compose -f docker-compose.slot-a.yml --env-file docker/multi-robot/machines/dev.env"

cmd="${1:-build}"
case "$cmd" in
  build)
    echo "==> $COMPOSE build app-slot-a"
    $COMPOSE build app-slot-a
    echo "==> 镜像构建完成（使用 Compose 配置的镜像标签）"
    ;;
  up)
    echo "==> $COMPOSE up -d --build"
    $COMPOSE up -d --build
    echo "==> 服务已启动: $COMPOSE ps"
    ;;
  start)
    echo "==> $COMPOSE up -d --no-build"
    $COMPOSE up -d --no-build
    echo "==> 服务已启动（未重新构建）"
    ;;
  down)
    echo "==> $COMPOSE down"
    $COMPOSE down
    ;;
  logs)
    echo "==> $COMPOSE logs -f app-slot-a"
    $COMPOSE logs -f app-slot-a
    ;;
  *)
    echo "用法: bash deploy.sh [build|up|start|down|logs]" >&2
    exit 1
    ;;
esac
