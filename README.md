# nav-deployer

仿真机器人上位机部署程序（Spring Boot 3 / Java 21 / PostgreSQL / Redis / MinIO / k3s）。

面向仿真/真实 AGV 的现场部署环节：建图、地图管理、点位与路线部署、机器人移动控制。
与机器人侧通过 rosbridge WebSocket（agv_bridge_v2）对接，全程 JSON，无 ROS 依赖。

## 功能模块（无页面 MVP）

设计文档与接口文档：`docs/nav-deployment.md`；rosbridge 对接细节：`rosbridge_integration.md`。

| 模块 | 能力 | 主要接口 |
|---|---|---|
| 建图 / 地图管理 | /map 实时栅格、快照建图（gzip 入 MinIO）、机器人地图列表/导入（get_map 入库）、激活部署 | `/api/v1/nav-maps`、`/robot-maps`、`/import-from-robot` |
| 地图模式任务 | 切图（load_map）、在线建图（start_mapping）、保存并自动同步（save_map）；mode 状态机跟踪，成功才对齐 ACTIVE | `/api/v1/nav-maps/{id}/switch`、`/api/v1/map-mode-tasks/*` |
| 点位部署 | CRUD、把机器人当前位姿标记为点位 | `/api/v1/nav-points`、`/api/v1/nav-points/from-current-pose` |
| 路线部署 | 有序边序列（直线/贝塞尔/倒车/限速）、连续性校验、部署 | `/api/v1/nav-paths`、`/{id}/edges`、`/{id}/deploy` |
| 移动任务 | 到点位 / 到坐标 / 按路线逐段执行 follow_edge，feedback+result 闭环、取消、看门狗 | `/api/v1/move-tasks` |
| 机器人控制 | set_control start/stop/reset、重定位、遥测快照（含 mode/map_name/新鲜度） | `/api/v1/robot/*`、SSE `/sse/agv` |

Swagger：http://localhost:8083/swagger-ui/index.html

## 本地运行

```bash
# 前置：WSL 内 k3s 中间件 + kubectl port-forward（见 docs/ops-stop-and-resume.md），
#       rosbridge 在 ws://127.0.0.1:9090
./mvnw spring-boot:run
```

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
