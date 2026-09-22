# 仿真机器人部署程序 · 功能清单与接口文档（v1）

> 定位：上位机（Spring Boot）侧的部署程序 MVP —— 不含页面，仅提供 REST + SSE 接口。
> 数据源与执行通道：`rosbridge_integration.md`（agv_bridge_v2 0.2.0）。
> 命名遵循 team-ai-rules 系统字典：`nav_map` / `nav_point` / `nav_path` / `move_task`。

---

## 1. 功能清单

| # | 功能 | 说明 |
|---|------|------|
| 1 | 建图 | 订阅 `/map`（Cartographer OccupancyGrid，5s 节流 + 分片重组），把实时栅格快照保存为一张地图（栅格 gzip 后存 MinIO，元数据存 PostgreSQL） |
| 2 | 地图管理 | 地图列表/详情/重命名/删除/数据读取；`activate` 把一张地图置为当前部署地图（全局唯一 ACTIVE，其余归档） |
| 3 | 点位部署 | 在指定地图上创建/修改/删除点位；支持「把机器人当前位姿标记为点位」（无页面时的实操部署方式） |
| 4 | 路线部署 | 路线 = 有序边序列（点到点，支持直线/贝塞尔曲线/倒车/限速）；整体替换边序列，带连续性校验；`deploy` 将路线置为已部署 |
| 5 | 移动控制 | 创建移动任务并下发 `/agv/follow_edge` action：到点位（TO_POINT）、到任意坐标（GOAL）、按路线逐段执行（FOLLOW_PATH）；支持取消 |
| 6 | 机器人控制 | `set_control` start/stop/reset；`/initialpose` 重定位 |
| 7 | 实时状态 | REST 快照 + SSE（`/sse/agv`：telemetry / heartbeat / task 事件） |
| 8 | 任务对账 | 每条任务生成 `command_id` 落库，`action_result` 回来按 goal id 关联更新状态；feedback 更新实时位置与 AGV 状态 |

**明确不做（本期）**：页面、多机器人、`/goal_pose` 自由导航下发（统一走 follow_edge 以获得对账与取消能力）、地图回灌机器人（仿真栈白名单未开放）、3D 点云。

## 2. 模块设计

```
controller/   NavMapController NavPointController NavPathController MoveTaskController RobotController
service/      NavMapService(建图/MinIO) NavPointService NavPathService(边校验) MoveTaskService(状态机/逐段执行)
sim/          RosbridgeClient(连接/订阅/收发) RosbridgeConfig(装配) SimAgvProperties(配置)
              LiveMapCache(/map 缓存) RosCommandDispatcher(action/service 关联) SimAgvTelemetry*(遥测) SimAgvSsePublisher
entity/mapper nav_map nav_point nav_path nav_path_edge move_task
```

任务状态机（`move_task.status`）：

```
CREATED ──下发──> DISPATCHED ──首个feedback──> EXECUTING ──┬─ status=4 → SUCCEEDED
   │（下发前校验：连接/定位收敛/无在途任务）                ├─ status=5 → CANCELLED
   └─ 校验失败 → FAILED                                     ├─ status=6 → FAILED
                                                            └─ 超时(600s) → cancel → TIMEOUT
FOLLOW_PATH：逐段下发 follow_edge，end_point=true 仅最后一段；任一段终态非成功则任务终止。
```

## 3. 接口文档

统一返回 `{"status":200,"msg":"success","data":...}`（`ApiResponse`），JSON 字段 snake_case。
错误：400（参数/业务拒绝）、404（NotFound）、500（兜底）。坐标单位：米 / 弧度（map 系）。

### 3.1 建图与地图管理 `/api/v1/nav-maps`

| 方法 | 路径 | 说明 | 关键入参 / data |
|---|---|---|---|
| GET | `/live` | 实时栅格（/map 最新一帧） | `{frame_id, info{resolution,width,height,origin{x,y,yaw}}, data[], received_at}`；未收到 → 400 |
| POST | `` | 从实时快照建图 | body `{map_name}`；201 返回 `NavMapVO` |
| GET | `` | 地图列表（元数据，不含栅格） | `NavMapVO[]` |
| GET | `/{id}` | 地图详情 | `NavMapVO`：id/map_name/status/source/resolution/width/height/origin_x/origin_y/origin_yaw/data_size/created_at/updated_at |
| GET | `/{id}/data` | 栅格数据（application/json，格式同 /map 的 msg） | OccupancyGrid JSON |
| PUT | `/{id}` | 重命名 | `{map_name}` |
| POST | `/{id}/activate` | 激活为当前部署地图 | 其余 ACTIVE → ARCHIVED |
| DELETE | `/{id}` | 删除（有关联点位/路线或处于 ACTIVE 时拒绝） | — |

### 3.2 点位 `/api/v1/nav-points`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `` | 创建：`{map_id, point_code, point_type(NORMAL/CHARGER/HOME), x, y, yaw, remark?}` |
| POST | `/from-current-pose` | 标记机器人当前位姿为点位：`{map_id, point_code, point_type?, remark?}`（读 /tf→map 位姿） |
| GET | `?map_id=` | 按地图列点位（map_id 必填） |
| GET/PUT/DELETE | `/{id}` | 详情 / 更新（x,y,yaw,point_type,remark,point_code）/ 删除（被路线引用时拒绝） |

### 3.3 路线 `/api/v1/nav-paths`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `` | 创建：`{map_id, path_code, path_name?}` |
| PUT | `/{id}` | 更新：`{path_name?, status?}` |
| PUT | `/{id}/edges` | 整体替换边序列：`{edges:[{source_point_id,target_point_id,edge_type(STRAIGHT/CURVE),control_points?[{x,y}](1~2),max_speed?,back_up?,reverse?}]}`；自动编号 seq；校验：点属于该地图、CURVE 必须带 1~2 个控制点、第 i 条 source 必须等于第 i-1 条 target（连续性） |
| GET | `?map_id=` / `/{id}` | 列表（不含边）/ 详情（含有序边） |
| POST | `/{id}/deploy` | 置为 DEPLOYED（要求已配置至少一条边；状态回到 DRAFT 需重新替换边） |
| DELETE | `/{id}` | 删除（连带边） |

### 3.4 移动任务 `/api/v1/move-tasks`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `` | 创建并下发。body：`{task_type, point_id?, x?, y?, theta?, path_id?, max_speed?, end_point?}`；TO_POINT 需 point_id（点位须位于 ACTIVE 地图）；GOAL 需 x/y/theta；FOLLOW_PATH 需 path_id（须 DEPLOYED）。返回 `MoveTaskVO`（含 task_no/command_id） |
| POST | `/{id}/cancel` | 取消：cancel_action_goal；终态 CANCELLED 由 action_result 确认 |
| GET | `/active` | 当前在途任务（无则 data=null） |
| GET | `?status=&limit=` | 任务列表（默认 50 条，新→旧） |
| GET | `/{id}` | 任务详情（含实时 feedback 位姿、FOLLOW_PATH 段进度） |

`MoveTaskVO`：id/task_no/task_type/status/point_id/path_id/goal_x/goal_y/goal_theta/current_x/current_y/current_theta/agv_state/segment_seq/segment_total/error_message/created_at/updated_at/finished_at。

下发前置校验（失败即 400，任务不创建或置 FAILED）：rosbridge 已连接、`pose_initialized=true`、无在途任务、（FOLLOW_PATH）路线已部署。

### 3.5 机器人 `/api/v1/robot`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/snapshot` | 遥测快照：connected/status(state,battery,pose_initialized,active_command_id)/pose(x,y,yaw)/scan1/scan2/消息计数 |
| POST | `/control` | `{action: start/stop/reset}` → `/agv/set_control`（同步等服务响应，5s 超时） |
| POST | `/initial-pose` | `{x,y,theta}` → publish `/initialpose`（PoseWithCovarianceStamped） |

### 3.6 SSE `/sse/agv`（已有，扩展）

事件：`connected` / `telemetry`（1s 聚合快照）/ `heartbeat`（30s）/ `task`（任务创建、状态变更、feedback 降频推送）。

## 4. 数据模型（PostgreSQL，schema.sql 幂等建表）

```
nav_map(id, map_name UNIQUE, status DRAFT/ACTIVE/ARCHIVED, source LIVE,
        resolution, width, height, origin_x, origin_y, origin_yaw,
        object_key, data_size, created_at, updated_at)
nav_point(id, map_id→nav_map, point_code, point_type, x, y, yaw, remark,
          created_at, updated_at, UNIQUE(map_id, point_code))
nav_path(id, map_id→nav_map, path_code, path_name, status DRAFT/DEPLOYED/DISABLED,
         created_at, updated_at, UNIQUE(map_id, path_code))
nav_path_edge(id, path_id→nav_path CASCADE, seq, source_point_id→nav_point,
              target_point_id→nav_point, edge_type, control_points TEXT(json),
              max_speed, back_up, reverse, UNIQUE(path_id, seq))
move_task(id, task_no UNIQUE, task_type, status, point_id, path_id,
          goal_x, goal_y, goal_theta, current_x, current_y, current_theta,
          agv_state, error_message, goal_id, segment_seq, segment_total,
          created_at, updated_at, finished_at)
```

对象存储：MinIO bucket `nav-maps`，key `nav-maps/{uuid}/grid.json.gz`（gzip 的 OccupancyGrid JSON）。

## 5. 配置（application.yaml → 环境变量）

```yaml
sim_agv:
  enabled / ws_url / 各 topic（沿用现状）
  map_enabled: true          # 订阅 /map
  map_topic: /map
  map_throttle_ms: 5000
  follow_edge_action: /agv/follow_edge
  follow_edge_action_type: agv_bridge_v2_interfaces/action/FollowEdge
  set_control_service: /agv/set_control
  initial_pose_topic: /initialpose
  goal_timeout_s: 600        # 单任务看门狗
  default_max_speed: 0.6
  default_step: 0.1
minio:
  maps_bucket: nav-maps
```

K8s：`app-config` ConfigMap 增加 `SIM_AGV_ENABLED` / `SIM_AGV_WS_URL`（Pod 内不能连 127.0.0.1，需指向 WSL 宿主 IP）。
