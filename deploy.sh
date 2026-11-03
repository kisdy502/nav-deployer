#!/usr/bin/env bash
# 仓库内一键 Docker 部署脚本（Windows Git Bash / Linux 通用）。
# 用法：
#   bash deploy.sh build   # 只构建应用镜像（前端 + 后端多阶段），不启动
#   bash deploy.sh up      # 构建并启动全部服务（postgres/redis/minio/app）——日常开发一条命令
#   bash deploy.sh start   # 不构建直接启动（用现有镜像；上位机代码未变时秒起，适合"稳定跑"模式）
#   bash deploy.sh down    # 停止并移除容器
#   bash deploy.sh logs    # 跟随应用日志
set -euo pipefail
cd "$(dirname "$0")"

cmd="${1:-build}"
case "$cmd" in
  build)
    echo "==> docker compose build app"
    docker compose build app
    echo "==> 镜像构建完成（使用 Compose 配置的镜像标签）"
    ;;
  up)
    echo "==> docker compose up -d --build"
    docker compose up -d --build
    echo "==> 服务已启动: docker compose ps"
    ;;
  start)
    echo "==> docker compose up -d --no-build"
    docker compose up -d --no-build
    echo "==> 服务已启动（未重新构建）: docker compose ps"
    ;;
  down)
    echo "==> docker compose down"
    docker compose down
    ;;
  logs)
    echo "==> docker compose logs -f app"
    docker compose logs -f app
    ;;
  *)
    echo "用法: bash deploy.sh [build|up|start|down|logs]" >&2
    exit 1
    ;;
esac
