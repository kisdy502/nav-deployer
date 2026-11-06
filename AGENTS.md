# AGENTS.md — nav-deployer 本地约定（Local Overrides）

本文件是 `docs/codeStyle/` 的本地覆盖入口，冲突时以本文件为准。

## 项目概述

nav-deployer：仿真机器人上位机（AGV 调度部署系统）。功能：建图与地图管理（V2 多楼层）、点位/路线部署、移动任务执行（follow_edge 逐段闭环 + 看门狗）、充电任务、机器人控制与 SSE 遥测、双机械臂状态监控、对接云端 RMS（zenoh）。

技术栈：Spring Boot 3.3.5 / Java 21 / PostgreSQL + Flyway / Redis / MinIO / MyBatis-Plus 3.5.7 / MapStruct / springdoc。机器人侧经 rosbridge WebSocket（JSON，无 ROS 依赖）。前端 Vue 3 + three.js，位于 `frontend/`。

## 规范入口

1. `docs/codeStyle/2-java-backend.md`、`3-java-anti-patterns.md`、`5-restful-api.md`、`6-code-comments.md`、`requirements-workflow.md` —— 唯一权威
2. `DDD参考代码/ota` —— DDD 落地模板（包结构、充血聚合、RepositoryImpl、防腐层、Mapstruct 写法照此抄；但**不抄**其中"领域/应用服务直接注入跨域 Mapper"的反模式）
3. 需求三级流在 `requirements/` 目录生效（specs/stories/tasks）

## 本仓 DDD 决策（超出 codeStyle 默认）

- **全仓强制 DDD 四层**：interfaces / application / domain / infrastructure 为每个 BC 模块内部包结构；Maven 模块 = 限界上下文（nav-common/nav-user/nav-task/nav-map/nav-robot-gateway/nav-rms/nav-app）
- 依赖方向强制：`interfaces → application → domain ← infrastructure`；跨 BC 只走对方 Repository 或 application，禁直接引对方 Mapper/DO
- 领域实体默认直接 `@TableName` 映射（无 DO）；表/实体差异大才引入 `XxxDO` + `XxxMapstruct`
- MapStruct：`componentModel = "spring"`，命名 `XxxMapstruct`，放 `infrastructure/mapstruct`
- 错误码：`BusinessException` + `ErrorCode` 接口在 nav-common，各 BC 自持 enum 实现
- 领域事件默认不引入；需要解耦用同步 `@EventListener` 且监听器内重查库；`@Async` 仅限时序无关场景
- 出参命名 `XxxDTO`（放 application/dto/），入参 `XxxCmd`，查询 `XxxQuery`
- 新 BC 模块脚手架照抄 `modules/user`：pom 依赖 nav-common/web/validation/cache/mybatis-plus/springdoc（按需 minio、mapstruct+processor）；Mapper 加 `@Mapper` 即被扫描（无全局 @MapperScan）；包根 `com.agv.navdeployer.{bc}` 与主应用同根包，免配置装配

## RESTful Local Overrides（响应与 URL 现状，勿"纠正"）

- 响应结构：`{"status": 200, "msg": "...", "data": ...}`，**status=200 表示成功**（HTTP 风格，勿改为 0）
- JSON 字段命名：snake_case（Jackson 全局 SNAKE_CASE）
- URL 现状：`/api/v1/*`（业务入口）与 `/api/v2/*`（V2 地图系）并存；资源名连字符小写复数（如 `/api/v2/map-waypoints`）；单复数沿用现状，不为单复数发起重构
- 新增接口遵循 `docs/codeStyle/5-restful-api.md`（URL 禁动词、子资源 ≤2 层、GET 禁改数据）
- id 对外输出保持现状（数值型），不做雪花字符串化
- 分页响应字段沿用现状

## 注释与测试

- 注释规范严格执行 `docs/codeStyle/6-code-comments.md`：禁解释性注释、禁方法 Javadoc；仅允许单行技术陷阱注释；对外接口说明用 Swagger `@Tag/@Operation/@Schema`
- **当前无自动化测试**（重构决策，见 `requirements/specs/ddd-refactor.md`）：story 验收 = 服务启动 + 冒烟清单 + 前端核心页可用；禁止为新代码编写"顺手"测试之外的测试

## 术语表（全仓唯一术语，禁自造缩写）

| 术语 | 含义 |
|---|---|
| 地图 map | V2 多楼层地图（map 表），发布后 PUBLISHED 状态方可执行任务 |
| 楼层 floor | map_floor |
| 点位 waypoint | map_waypoint，11 类词表（NORMAL/WORK/CHARGER/HOME 等） |
| 区域 zone / 货架 shelf | 禁行减速区 / 货架层位（含 layer、slot） |
| 路线 path / 路段 segment | map_path + map_path_segment（含贝塞尔/倒车/限速） |
| 移动任务 move_task | 类型：TO_POINT / GOAL / FOLLOW_PATH / FREE（新增 to_waypoint 为 V2 点位导航） |
| 建图任务 map_mode_task | 建图/切图/保存闭环 |
| 充电任务 charge | V2 充电点位导航 + 智能路径规划（smart navigation） |
| RMS | 云端机器人管理服务（zenoh 双向通道） |
| rosbridge | 机器人侧 WebSocket JSON 协议（agv_bridge_v2） |
| .lxmap | 旧调度器兼容拓扑文件（从 V2 数据投影生成） |

## V1 下线警告

V1 地图体系（nav_map / nav_point / nav_path / nav_path_edge、`/api/v1/nav-maps|nav-points|nav-paths`、前端 v1 编辑器 `/editor/:id`）**已废弃、正在下线**。禁止新代码引用 V1 实体、Mapper、Service；发现存量引用随 story 清除。下线全程见 `requirements/specs/ddd-refactor.md`。
