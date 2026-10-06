# RMS 对接开发需求（zenoh 机器人侧客户端）

> 目标：上位机（nav-deployer）内嵌一个 zenoh 机器人客户端，替代 qcrobotmock 在
> RMS（robot-management-service）体系里的角色——接收 RMS 下发的任务指令、上报
> 机器人状态；与 mock 的本质区别：**移动/作业由仿真机器人真实执行**
> （复用现有 MoveTaskService → agv_bridge_v2 → follow_edge 链路），
> 而不是模拟坐标跳变。
>
> 参考实现：`D:\workspace\qc-robot\qcrobotmock_new`（协议行为参考，代码不参考——
> 其 Map/String 拼接风格是本次要规避的反面教材）。
> 协议权威方：`robot-management-service` 的 zenoh 模块。

## 1. 协议契约（从 mock/RMS 双侧源码提炼）

### 1.1 Key 表达式

| 用途 | Key | 模式 |
|---|---|---|
| 注册 | `zioneer/robot-management-service/api/v1/robot-mgr/register` | query（robot 发 query，RMS 回复） |
| 心跳 | `zioneer/{robot_type}/robot/{robot_code}/api/v1/heartbeat` | query + 双向发布（另有 legacy `...robot/{robot_code}/heartbeat` put） |
| 状态上报 | `.../api/v1/status/report`（body）+ `...robot/{robot_code}/status/report`（legacy） | robot 定时 put |
| 任务指令 | `.../api/v1/task/add` `task/start` `task/pause` `task/resume` `task/stop` `task/status` `task/delete` | RMS 发 query，robot 是 queryable，同步回复 BodyReply |
| 单任务指令 | `.../api/v1/task/{task_id}/pause` `resume` `stop`（通配） | 同上（注意 RMS 存在 `task/puase` 拼写兼容） |
| 任务结果 | `.../api/v1/task/{task_id}/result_report` | robot 完成后 put |
| 结果文件 | `.../api/v1/task-result-files-uploaded` | robot put（V2） |
| 模板 | `.../api/v1/task_template/add` `query` `delete` | queryable |
| 地图 | `.../api/v1/mapping/*`（get/download/list/...） | queryable（按需裁剪） |

### 1.2 报文（全部 JSON、snake_case）

- **BodyReply（robot → RMS 同步回复）**：`{status:int, msg:string, data:object}`
- **心跳/状态上报字段**（mock 的 buildRuntimeReportPayload 为准）：
  robot_code / robot_sn / serial_no / online_status / location / battery /
  mileage_km / current_map_code / current_map_name / dispatch_mode /
  control_status / exception_status / video_status / chassis_mode / arm_mode /
  is_charging / is_homing / is_lifted / map_name / position{x,y,yaw} /
  compatible_chargers / rmf_compatible_chargers / body_task_id /
  body_task_status / reported_at
- **任务指令请求**：task_id / action_id / task_type / task_template_type /
  template_code / template_id / parameters（内含 navigate 的 destination{x,y,yaw}、
  charge 的 charger 名等）
- **任务状态码映射**（action_status）：
  accepted=1，running/paused=2，completed/success=3，failed=4，aborted=5，canceled/stopped=6
- **result_report 信封**：`{status:0, data:{task 报告}, timestamp:epoch_ms}`

### 1.3 任务生命周期（RMS 视角）

```
task/add → accepted ── task/start → running ──→ completed ──→ result_report(put)
                         │  ↑                    ├─→ failed ────→ result_report
                pause ───┘  └── resume           └─→ canceled(stopped)
```

多阶段作业（同 task_id 复用）：RMS 的质检作业先发 navigate 阶段、到达后再发
quality_inspection 阶段，【两阶段 task_id 相同】。robot 侧语义：task/start 对
已终态的同 ID 任务按新阶段参数重建执行（见 RmsTaskService.startTask），
在途（RUNNING/PAUSED）时仍拒绝，防真重复下发。

内置模板类型：`rmf_navigate`/navigate、`quality_inspection`、`charge`、
`replace_battery`、`return_home`、`pause_task`、`resume_task`。

## 2. 模块划分（包结构 com.agv.navdeployer.rms）

## 2.0 交互模式总览（先看这个再看结构）

协议只有三种交互模式，代码按方向硬隔离——打开任何 gateway 只见一个方向：

| 模式 | 方向 | zenoh 原语 | 话题 | 代码归属 |
|---|---|---|---|---|
| A 发布 | robot→RMS（RMS 订阅） | put | status/report（body+legacy）、legacy heartbeat、task/{id}/result_report | RmsReportGateway |
| B 机器人查询 | robot→RMS（RMS 应答） | query | register、heartbeat | RmsServiceGateway |
| C RMS 查询 | RMS→robot（本机 queryable） | queryable + reply | task/*、task_template/*、status/query、config、mapping/* | RmsCommandGateway |
| — | ~~RMS 发布、robot 订阅~~ | — | 协议中不存在此方向 | — |

心跳是 A+B 混合体（legacy put + body query 双通道，协议历史包袱），由 Session 同节拍调用两个网关。

## 2.1 实际包结构（as-built）

```
rms/
├── config/     RmsProperties（开关/身份/上报周期/行为参数；zenoh 连接走 config-json5.json）
│               RmsConfig（装配：keys + 三网关 + session）
├── protocol/
│   ├── keys/   ★ 按方向分组
│   │           RmsReportKeys（A：状态/结果/legacy 心跳 key）
│   │           RmsServiceKeys（B：register、heartbeat query key）
│   │           RmsCommandKeys（C：指令端点 key + 段/taskId 解析）
│   │           BodySegment（C 段枚举，含 supported 501 标记）
│   │           RobotKeyContext（包私有，key 拼接共用身份）
│   └── dto/    ★ 按模式分组（全部 record + Jackson snake_case）
│       ├── report/   RobotStatusReport(+心跳) / TaskResultEnvelope   ← A 报文
│       ├── service/  RegisterRequest                                 ← B 报文
│       └── command/  BodyReply / TaskCommandRequest / TaskInfoReport / BodyStatusData ← C 报文
├── gateway/    ★ 三个方向网关
│   ├── RmsReportGateway（A：putOnce 状态/结果/legacy 心跳；实现 RmsResultReporter.Sink）
│   ├── RmsServiceGateway（B：register/heartbeat query，等 RMS 应答）
│   └── RmsCommandGateway（C：attach 声明全部 queryable → 路由 RmsTaskService，同步快答）
├── session/    RmsRobotSession（只剩生命周期编排：重建→注册→声明→调度）
├── state/      RobotStateView（状态唯一出口：遥测+任务+bridge mode → 报文）
├── task/       RmsTaskService / RmsTaskRegistry / RmsTask / RmsTaskStatus /
│               RmsResultReporter（结果上报出口，sink 由 ReportGateway 实现）
└── zenoh/      ZenohChannel（会话/资源管理 + put/query/queryable 封装，加载 config-json5.json）
```

### 职责边界（防止烂掉的三条铁律）

1. **protocol 层只有 record + mapper**：任何 payload 构造不允许出现
   `Map<String,Object>` 手拼；与 mock 历史报文做序列化快照测试保证兼容。
2. **state 层单一出口**：RMS 看到的机器人状态只能来自 `RobotStateView`
   （聚合 SimAgvTelemetry 位姿/电量 + MoveTaskService 在途任务 +
   bridge mode），其他层不许直接读遥测。
3. **task 层只编排不执行**：RmsTaskExecutor 一律委托现有
   MoveTaskService / 后续作业服务，自身不持有机器人为模型。

### 状态字段映射（RobotStateView）

| RMS 字段 | 来源 |
|---|---|
| online_status | rosbridge 连接状态（connected → online） |
| position | SimAgvTelemetry.mapPose（map 系 x/y/yaw） |
| battery | /agv/status.battery（仿真恒 100） |
| control_status / body_task_status | RMS 任务运行时状态（RmsTaskRegistry） |
| current_map_name / map_name | bridge status.map_name（load_map 后同步） |
| is_charging / is_homing | 任务类型推导（charge/return_home 执行中） |
| mileage_km | 里程累计器（odom 速度积分，V2，先置 0） |
| compatible_chargers | 配置（nav 点位中 CHARGE 类型点，V2） |

## 3. 关键设计决策

### 3.1 queryable 回复必须快
RMS 的 task/start 等 query 同步等待。Dispatcher 回调里只做：
参数校验 → RmsTaskRegistry 登记 → 提交异步执行 → 立即回 BodyReply(running)。
真实移动由 worker 线程调 MoveTaskService.create()，完成事件回流后 put
result_report。**禁止**在 zenoh 回调线程里发起 follow_edge 等待。

### 3.2 移动执行映射

| RMS 模板 | 内部动作 |
|---|---|
| rmf_navigate / navigate | MoveTaskService.create(GOAL, destination.x/y/yaw) |
| charge / replace_battery | navigate 到充电点点位（charge 桩坐标，配置或 CHARGE 点）→ 置 is_charging → 完成 |
| return_home | navigate 到 home 点位 → is_homing → 完成 |
| quality_inspection | **占位执行器（已确认）**：仿真机械臂后续接入；当前固定延时后上报模拟结果，接臂时只换 executor |
| pause_task / resume_task | 见 3.3 |

### 3.3 暂停/恢复的降级语义（已确认：简化实现）
pause = 取消内部移动并置 paused；resume = 按任务目标快照重新下发移动。
RMS 侧已认可该简化语义。

### 3.4 坐标系（已确认）
RMS 与机器人同用 ROS 坐标系，与 RMF 无关——destination 直接下发给
MoveTaskService，无需 CoordinateTransformer。

### 3.5 会话可靠性
- zenoh Session 断开重连后：重新 declare 全部 queryable/publisher、重走注册、
  恢复心跳/状态调度（对齐 RosbridgeClient 的成熟模式）。
- 注册失败策略可配置：`continue-without-register` / 启动失败。

### 3.6 与现有功能并行
`rms.enabled=false` 默认关闭；开启后与 rosbridge 通道互不影响。
SSE/前端不动；后续可在前端加 RMS 任务面板（读 RmsTaskRegistry）。

## 4. 依赖与部署注意

- `org.eclipse.zenoh:zenoh-java:1.8.0`（与 mock 同版本，**native 库要求
  glibc ≥ 2.39** —— k8s 镜像需从 distroless 换 ubuntu 基底或验证 glibc）。
- TLS/mTLS：沿用 mock 的场景证书方案（config/certs，transport: tcp|tls）。
- robot-management REST 预置（ensure group/type）：可选实现，默认关。

## 5. 分期计划

| 里程碑 | 内容 | 验收 |
|---|---|---|
| M1 通道底座 | zenoh session/topics/DTO/注册/心跳/状态上报 | RMS 页面看到机器人在线、位姿随仿真实时变化 |
| M2 任务闭环 | task add/start/status/stop + navigate 真实执行 + result_report + pause/resume 降级 | RMS 下发导航 → 仿真机器人真实移动 → RMS 收到完成；中途停止生效 |
| M3 完善 | 模板 query、charge/return_home、异常上报、断线重注册、结构化指令日志 | 全生命周期回归 |
| M4 可选 | result_files 上传、mapping 子集、前端 RMS 任务面板、里程积分 | 按需 |

## 6. 测试策略（mock 完全没有的部分）

- protocol：DTO ↔ JSON 快照测试（用 mock 联调期间抓的真实报文做金样本）；
  RmsTopics 构造测试。
- task：RmsTaskStatus 状态机全迁移用例；Dispatcher 参数校验用例。
- 集成：本地起 zenoh router（`zenohd`）+ mock 模式的 RMS，跑 M1/M2 验收。
