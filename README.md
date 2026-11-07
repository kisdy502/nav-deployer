# nav-deployer

仿真机器人上位机部署程序（Spring Boot 3 / Java 21 / PostgreSQL / Redis / MinIO / k3s）。

面向仿真/真实 AGV 的现场部署环节：建图、地图管理、点位与路线部署、机器人移动控制。
与机器人侧通过 rosbridge WebSocket（agv_bridge_v2）对接，全程 JSON，无 ROS 依赖。

## 功能模块

设计文档与接口文档：`docs/nav-deployment.md`；rosbridge 对接细节：`rosbridge_integration.md`；重构规范与模块架构：`AGENTS.md` + `requirements/specs/ddd-refactor.md`。

| 模块 | 能力 | 主要接口 |
|---|---|---|
| 建图 / 地图管理（V2 多楼层） | /map 实时栅格、快照建图、建图保存自动入库发布（PUBLISHED）、地图包导入导出（含 .lxmap 兼容） | `/api/v2/maps/*`、`/api/v2/maps/live` |
| 地图元数据 | 楼层/点位（11 类）/区域/货架/路线（贝塞尔段）CRUD、发布、Dijkstra 路径规划 | `/api/v2/map-floors|map-waypoints|map-zones|map-shelves|map-paths|map-inter-floor-conns`、`/api/v2/route/plan` |
| 地图模式任务 | 切图（load_map+自动发布）、在线建图、保存自动同步、周期对账；mode 状态机跟踪 | `/api/v1/map-mode-tasks/*`、`/api/v2/maps/{id}/switch` |
| 移动任务 | 按 V2 点位 / 到坐标 / 按路线逐段执行 follow_edge，feedback+result 闭环、取消、看门狗 | `/api/v1/move-tasks` |
| 充电任务 | 智能路径规划导航到桩、对接确认、救援充电 | `/api/v1/charge/*` |
| 机器人控制 | set_control start/stop/reset、重定位、遥测快照（含 mode/map_name/新鲜度） | `/api/v1/robot/*`、SSE `/sse/agv` |
| RMS 云端对接 | zenoh 双向通道：注册/心跳/状态上报/任务派发/地图上传下载 | 无本地 HTTP，云端发起 |

后端 Maven 模块（限界上下文）：`nav-common`（共享内核）、`nav-user`、`nav-task`、`nav-map`、`nav-robot-gateway`、`nav-rms`、`nav-app`（装配，唯一 fat jar）。各模块内部 DDD 四层（interfaces/application/domain/infrastructure），详见 `AGENTS.md`。

Swagger：http://localhost:8083/swagger-ui/index.html

## 本地运行

```bash
# 前置：WSL 内 k3s 中间件 + kubectl port-forward（见 docs/ops-stop-and-resume.md），
#       rosbridge 在 ws://127.0.0.1:9090
./mvnw spring-boot:run
```

## Docker 打包与部署

完整步骤见 [Docker 打包与部署](docs/docker-deployment.md)，包括 `.env`、机器人网络、
Zenoh 文件与质检图片挂载、局域网访问和升级。

```bash
# 先启动仿真机器人，按文档准备 .env 和挂载目录
docker compose config --quiet
bash deploy.sh up  # 已包含构建，无需先单独 build
# PowerShell 可直接使用：docker compose up -d --build
```

镜像包含完整前后端，本机访问 http://localhost:8083，其他设备使用宿主机局域网 IP 的 8083 端口。

## 部署到 k3s（WSL 内）

```powershell
kubectl apply -f k8s\            # 全套 manifests（namespace=nav-deployer）
powershell -ExecutionPolicy Bypass -File k8s\deploy.ps1   # 构建+导入+发版应用镜像
```

注意：
- Pod 内不能连 127.0.0.1，`SIM_AGV_WS_URL` 需配置为 WSL 节点 IP
  （`k3s kubectl get node -o wide` 的 INTERNAL-IP，见 `k8s/01-configmap.yaml`）
- MinIO 预签名 URL 走 NodePort/端口转发，浏览器访问依赖 `MINIO_EXTERNAL_PORT` 配置

## k8s 运维速查

```powershell
wsl -u root -- k3s kubectl get pods -n nav-deployer -w           # 看状态
wsl -u root -- k3s kubectl logs -n nav-deployer deploy/nav-deployer -f   # 日志
wsl -u root -- k3s kubectl rollout undo deployment/nav-deployer -n nav-deployer  # 回滚
kubectl delete -f k8s/ && kubectl apply -f k8s/                  # 重新部署（PVC 数据保留）
```

# 环境停止与恢复

见 `docs/ops-stop-and-resume.md`。

# 仿真机器人源码目录
见 /virtual-agv/** 启动方式和接口对接说明，由于代码持续更新，文档没有及时更新，以代码实现为准


# 日常调试
docker compose up -d postgres redis minio
