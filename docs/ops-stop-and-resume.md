# 环境停止与恢复手册（Docker / WSL k3s）

本机开发环境分四层，卡顿时按 **从上到下** 停，恢复时按 **从下到上** 起。所有操作均保留数据。

```
第4层  kubectl port-forward 隧道（pgAdmin/本地开发连库、MinIO 访问用的临时隧道）
第3层  k3s（systemd 服务，直接跑在 Ubuntu WSL 发行版内，含全部 Pod 与本地 PVC）
第2层  Ubuntu WSL 发行版（k3s + rosbridge 仿真栈都住这里）
第1层  Docker Desktop（WSL2 虚拟机，仅构建/导出应用镜像时需要）
```

> 历史说明：项目早期用 k3d（K3s 跑在 Docker 容器里），现已迁移为 WSL 内 systemd 直装的 k3s，
> namespace 统一为 `nav-deployer`。

## 一、停止（电脑卡 / 不用了）

```powershell
# 1. 杀掉后台 port-forward 隧道（WSL 内启动的 k3s kubectl port-forward）
wsl -u root -- sh -c "pkill -f 'kubectl port-forward' || true"
Get-Process kubectl -ErrorAction SilentlyContinue | Stop-Process -Force

# 2. 停 k3s（Pod 状态/PVC 数据全保留）
wsl -u root -- systemctl stop k3s

# 3. 退 Ubuntu WSL（释放 WSL2 内存，k3s 下次随发行版自启）
wsl --terminate Ubuntu-24.04

# 4. （仅构建镜像需要）退 Docker Desktop
#    托盘图标右键 -> Quit Docker Desktop
Get-Process | Where-Object { $_.Name -in @('Docker Desktop','com.docker.backend','com.docker.build') } | Stop-Process -Force -ErrorAction SilentlyContinue

# 验证：以下命令应只剩默认提示或为空
wsl --list --running
```

## 二、恢复（下次要用）

```powershell
# 1. 启动 Docker Desktop（仅当要构建/发版镜像时；开始菜单，等托盘图标变绿）

# 2. 启动 Ubuntu WSL（systemd 会自动拉起 k3s）
wsl -d Ubuntu-24.04 -- echo started

# 3. 验证（等 all Running 即恢复完成）
wsl -u root -- k3s kubectl get pods -n nav-deployer

# 4. 按需起 port-forward 隧道（在 WSL 内后台执行）
#    本地开发连库 / MinIO：
wsl -u root -- sh -c "nohup k3s kubectl port-forward -n nav-deployer svc/postgres 5432:5432 >/tmp/pf-5432.log 2>&1 & nohup k3s kubectl port-forward -n nav-deployer svc/redis 6379:6379 >/tmp/pf-6379.log 2>&1 & nohup k3s kubectl port-forward -n nav-deployer svc/minio 9000:9000 >/tmp/pf-9000.log 2>&1 &"

# 5. 访问入口（应用隧道）
wsl -u root -- sh -c "nohup k3s kubectl port-forward -n nav-deployer svc/nav-deployer 8083:8083 >/tmp/pf-8083.log 2>&1 &"
#    页面        http://localhost:8083
#    Swagger     http://localhost:8083/swagger-ui/index.html
#    MinIO 控制台 http://localhost:9001（另起隧道 svc/minio 9001:9001，或经 WSL IP:31001）
```

## 三、按需单停某一项（不想全停时）

```powershell
# 只停应用（数据库等继续跑）
wsl -u root -- k3s kubectl scale deployment/nav-deployer -n nav-deployer --replicas=0
# 恢复
wsl -u root -- k3s kubectl scale deployment/nav-deployer -n nav-deployer --replicas=2

# 只停整个 K8s 工作负载（保留 k3s 进程）
wsl -u root -- k3s kubectl delete -f k8s/     # 注意：PVC 里的数据仍保留
# 恢复
kubectl apply -f k8s/
```

## 四、注意事项

- **数据都在哪**：数据库/地图快照在 k3s 的 local-path PVC（WSL 文件系统
  `/var/lib/rancher/k3s/storage`），`systemctl stop k3s` / `wsl --terminate` 都不丢数据；
  删除 namespace 或 PVC 才会丢
- **仿真栈**：rosbridge(9090) 与 Gazebo 跑在 Ubuntu WSL 内，与 k3s 无关，单独启停
- **Pod 内连 rosbridge**：`SIM_AGV_WS_URL` 必须填 WSL 节点 IP
  （`k3s kubectl get node -o wide` 的 INTERNAL-IP），不能填 127.0.0.1
- **卡顿根因排查**：任务管理器里 `vmmem` / `vmmemWSL` 进程 = WSL2 虚拟机内存，按上面 2、3、4 步释放
- **应用发版**：`powershell -ExecutionPolicy Bypass -File k8s\deploy.ps1`（构建 → 导入 WSL k3s → 滚动更新）
