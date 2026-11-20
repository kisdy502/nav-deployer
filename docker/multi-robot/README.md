# 多机多车部署速查（槽位模型）

> 多台闲置电脑模拟多车调度，全部注册到**同一个 RMS**。
> 配置只有 4 份（槽 A/B/C/D），**所有电脑共用**；每台电脑唯一的差异是 `machines/<机号>.env`（该机各槽位的 RMS 身份）。
> 任何电脑上：**第 1 台车前端永远是 5173**，第 2 台 5174，第 3 台 5175，第 4 台 5176。

## 机器布局（当前）

| 机器 | 槽位 | 车数 | RMS 身份（见 machines/xx.env） |
|---|---|---|---|
| 10.6.32.205 | A + B | 2 | jy-devlopment-...-txss（沿用已注册）+ jy-agv003-...-multicar（沿用已注册） |
| 10.6.32.206 | A + B | 2 | jy-agv004/005-20261118-fleet |
| 10.6.32.207 | A + B + C | 3 | jy-agv006/007/008-20261118-fleet |
| 10.6.32.208 | A | 1 | jy-agv009-20261118-fleet |

新增电脑 = 加一个 `machines/<机号>.env`（写 SLOTS + 各槽 SN/NAME），然后逐槽 deploy。

## 槽位端口总表（每台机器相同）

| 槽 | 上位机容器 | 前端 | 后端 | 仿真栈目录 | 桥 ws | 桥 http | 数据库 | ROS domain |
|----|-----------|------|------|-----------|-------|---------|--------|-----------|
| A | nav-deployer-a | 5173 | 8083 | D:\workspace\simulated_chassis | 9090 | 8080 | nav_deployer（沿历史名，保数据） | 1 |
| B | nav-deployer-b | 5174 | 8183 | D:\workspace\chassis-slot-b | 9091 | 8081 | nav_deployer_b | 2 |
| C | nav-deployer-c | 5175 | 8184 | D:\workspace\chassis-slot-c | 9092 | 8082 | nav_deployer_c | 3 |
| D | nav-deployer-d | 5176 | 8185 | D:\workspace\chassis-slot-d | 9093 | 8084 | nav_deployer_d | 4 |

## 日常命令（记不住就翻这里）

```powershell
# 【最常用】改了上位机代码，某台机器全部车重建（在对应机器上执行）
powershell -ExecutionPolicy Bypass -File D:\workspace\nav-deployer\docker\multi-robot\rebuild-deployer.ps1 -Machine 205

# cd到项目根目录，只重建该机的部分槽位 / 跳过 git pull
powershell -ExecutionPolicy Bypass -File docker/multi-robot/rebuild-deployer.ps1 -Machine 205 -Slot A,B
powershell -ExecutionPolicy Bypass -File docker/multi-robot/rebuild-deployer.ps1 -Machine 205 -NoPull

# 新机器/新车首次部署（每槽一次：仿真栈 + 建库 + 上位机）
powershell -ExecutionPolicy Bypass -File deploy-slot.ps1 -Slot A -Machine 206
powershell -ExecutionPolicy Bypass -File deploy-slot.ps1 -Slot B -Machine 206

# 看日志 / 停起单个槽位
docker logs -f nav-deployer-b
docker compose --env-file docker\multi-robot\machines\205.env -f docker-compose.slot-b.yml -p nav-deployer stop app-slot-b

# 本机开发（dev，槽 A 单车）＝ bash deploy.sh up（内部自动带 machines/dev.env）
```

## 一次性环境准备（每台新电脑）

1. 克隆两个仓库到固定路径：`D:\workspace\nav-deployer`（分支 dev-ddd）、`D:\workspace\simulated_chassis`（槽 A 仿真栈源头，docker/ 与 maps/ 也从这里复制给 B/C/D）
2. 启动基础设施（qc-robot 平台栈：robot-postgres / robot-redis / robot-ceph-rgw / zenoh router）
3. 仓库加 `machines/<本机机号>.env` 并提交
4. 逐槽 `deploy-slot.ps1`，每槽前端导入并发布同一份地图包

## CPU 钉核分配（多槽同机必看）

仿真 Gazebo 稳态 ~1 核、桥+定位+导航栈 2-3 核，不钉核会互抢导致"旋转无进展/遥测冻结"。分配（16 核起配）：

| 槽 | 仿真 cpuset | 桥 cpuset | 说明 |
|----|------------|-----------|------|
| A | 0,1（sim 仓库钉死） | 2-6（sim 仓库钉死） | 不可改，除非动 simulated_chassis 仓库 |
| B | 7,8 | 9,10,11 | 模板默认，.env 可覆盖 |
| C | 12,13 | 14,15 | 模板默认，.env 可覆盖 |
| D | 不钉（浮动） | 不钉（浮动） | 24 核以上跑满 4 槽再启用（模板内有注释行） |

- 机器不足 16 核时只跑槽 A（8 核刚好覆盖 A 的 0-6）；cpuset 越界容器直接起不来
- 上位机容器不钉核（JVM 负载中等，浮动即可）

## 关键规则与坑

- **RMS SN 全局唯一是硬约束**：四个槽位 compose 均用 `${RMS_SN_x:?}` 强制校验，缺 env 直接报错起不来（防止两台机器顶号）
- 所有槽位共用 compose project `nav-deployer`：任何 `up/down` **不要加 `--remove-orphans`**（会把其它槽当孤儿删掉）
- 存量迁移自动化：`deploy-slot.ps1` 会把 205 时代旧目录 `chassis-agv003/4/5` 改名 `chassis-slot-b/c/d`（地图保留），并清掉旧容器 `nav-deployer-app` / `nav-deployer-agv00N`
- 每槽数据库为空库，首次部署后必须经该槽前端导入 + 发布地图（槽 A 沿用历史库 `nav_deployer`，老机器数据不丢）
- 地图导入 409（机器人侧已存在同名图）：停该槽仿真栈 → 清 `chassis-slot-x\maps` → 重启 → 重导
- 机器配置高低决定开几个槽（每个仿真车 CPU 开销大），加槽 = `deploy-slot.ps1 -Slot C`，前提 env 里有对应 SN
- 镜像 build 只在 `docker-compose.slot-a.yml`（每台机器必有槽 A，用它构建后其余槽位共享镜像）
