# DDD 全仓重构与 V1 地图下线

## 背景与动机

nav-deployer 现为典型三层（controller/service/mapper）模块化单体，业务逻辑堆积在 Service（MoveTaskService 675 行、RmsTaskService 827 行），贫血模型。团队规范已切换为 `docs/codeStyle/`（权威），并提供 `DDD参考代码/ota`（即 codeStyle 引用的 robot.ota 包）作为落地模板。本次重构将全仓迁移到 DDD 四层结构，同时借机下线已废弃的 V1 地图体系。

## 范围

- 后端 Java 全仓（modules/）+ 前端 V1 相关页面的外科手术式删除
- 不改变 V2 REST API 请求/响应结构、不改变数据库既有表数据语义
- V1 四表（nav_map/nav_point/nav_path/nav_path_edge）在功能下线后 DROP

## 目标架构

**Maven 模块 = 限界上下文，DDD 四层（interfaces/application/domain/infrastructure）为模块内包结构。**

| 模块 | 限界上下文 | 内容 |
|---|---|---|
| nav-common | 共享内核 | ApiResponse、BusinessException、ErrorCode 接口（最小化，禁止膨胀） |
| nav-user | 用户 | users 表 |
| nav-task | 任务 | move_task、map_mode_task、充电、智能导航；domain 定义 RobotCommandPort 等端口 |
| nav-map | 地图 | V2 十表模型、地图包交换（.lxmap 兼容导出）、LiveGridPort |
| nav-robot-gateway | 机器人接入 | rosbridge 客户端、遥测、SSE、机械臂监控；实现各 BC 端口（纯依赖倒置） |
| nav-rms | RMS 对接 | RmsTask 聚合、zenoh 通道；协议 DTO 全部压入 infrastructure |
| nav-app | 装配 | 唯一可运行模块（fat jar）、全局异常处理、Spring 配置 |

依赖方向：`nav-app → 全部；nav-rms → nav-task、nav-map（application 层编排）；nav-robot-gateway → nav-task、nav-map（实现端口）；各 BC → nav-common；BC 之间不横向依赖 domain 内部`。

## 模板与规范（冲突裁决顺序）

1. `docs/codeStyle/` 文本为唯一权威
2. `DDD参考代码/ota` 的务实做法优先于规范文本的理想化描述，但其中两处已知反模式**不抄**：领域服务/应用服务直接注入跨域 Mapper（走对方 Repository 或 application 层）
3. 关键模板决策：
   - 默认**无 DO 层**，领域实体直接 `@TableName` 映射；仅表/实体差异大时引入 DO + Mapstruct
   - MapStruct：`componentModel = "spring"`，位于 `infrastructure/mapstruct`，命名 `XxxMapstruct`，用于 Domain→DTO、跨域防腐、外部 DTO→领域
   - 出参命名 `XxxDTO`（application/dto/），入参 `XxxCmd`、查询 `XxxQuery`
   - 错误码：各 BC 自持 enum 实现 nav-common 的 ErrorCode 接口
   - domain/config/ 允许放 `@ConfigurationProperties`
   - 领域事件默认不引入；需要解耦处用同步 `@EventListener`（监听器内重查库），`@Async` 仅在时序无关场景使用（保持现有同步时序行为）

## V1 下线方案

V1 地图（nav_map/nav_point/nav_path/nav_path_edge、v1 编辑器、v1 API）已废弃：

- **不断的功能**：FOLLOW_PATH 执行（V2 优先链路）、充电（纯 V2）、.lxmap 导出（数据源已是 V2）
- **存量数据不迁移**（用户决策）：V1 数据全部废弃，随下线故事直接 DROP 四表
- **功能移植**（独立 story）：建图-切图-对账闭环 V2 化；to_waypoint 按 V2 点位导航；`/api/v2/maps/live` 等端点重建；RMS mapping/delete V2 化
- **随 V1 消亡**：TO_POINT 旧语义、v1 编辑器页面与 store、旧六件套 schedule-zip 端点、V1 兜底分支、迁移入口
- **收尾**：下线故事内 Flyway DROP 四表（V7）

## 执行纪律

- **分支**：refactor/ddd；每个 story 至少一次 commit，message 与 story 标题一致（`type: 标题`）
- **无自动化测试**：存量 32 个测试类删除（git 历史即归档）；每个 story 验收 = 服务启动 + 冒烟清单（对照 docs/nav-deployment.md 接口表 curl）+ 前端核心页可用
- **注释**：全仓 Java 清理解释性注释与方法 Javadoc（保留单行技术陷阱注释）；规范见 docs/codeStyle/6-code-comments.md
- **前端**：只做 V1 删除与指向 V2 的调用修正，不做 UI 重构
- **顺序**：数据迁移 → 功能补齐 → V1 下线 → BC 搬迁（user 试点立模板 → task → map → robot-gateway → rms）→ 装配收尾 + DROP 表
- **AGENTS.md** 为本地覆盖权威（响应格式 status:200、URL 现状、命名裁决）

## 风险（已知情接受）

1. 无自动化测试，安全网只剩冒烟清单——每个 story 必须逐条过验收标准
2. 建图闭环移植是最大单块新功能（MapModeTaskService 676 行逻辑重写到 V2 模型）
3. 数据迁移一次成败取决于 migrateFromNavMap 的质量，迁移后需人工核对拓扑完整性
4. move_task.point_id/path_id 历史数据语义混杂，DROP 表前需写明处理口径
