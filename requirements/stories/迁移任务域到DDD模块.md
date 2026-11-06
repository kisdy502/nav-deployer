# 迁移任务域到DDD模块

状态: 规划中

## 背景与动机

nav-task 是核心 BC：move_task（675 行 Service + 状态机 + 看门狗）、map_mode_task（V2 闭环）、充电与智能导航。按试点模板把业务逻辑从 Service 搬进充血聚合，并以端口接口倒置机器人依赖。

## 角色与意图

As a 开发者，I want 任务域以 DDD 四层独立成模块，So that 任务状态机与业务规则内聚在领域对象、机器人通信可替换。

## 功能描述

1. 新建 nav-task 模块：MoveTask/MapModeTask/ChargeTask 聚合（状态机入实体、checkXxx/领域迁移方法）、TaskRepository、领域服务（跨实体判断）、application 编排（现 Service 的流程控制部分）
2. domain 定义 RobotCommandPort（下发指令）、TelemetryPort（遥测读取）等端口，由 nav-robot-gateway 实现（本 story 先定义接口并保留现有调用实现的临时桥接）
3. MapV2 相关查询依赖经端口或 nav-map application（跨 BC 规则）
4. 对外 API 零变化，控制器迁入 interfaces 层并补 Swagger

## 业务规则

- R1: application 层无状态 if 分支（判断全部下沉实体/值对象/领域服务）
- R2: 端口接口定义在 nav-task domain，不 import 任何 rosbridge 实现类
- R3: 看门狗/feedback 闭环时序行为不变（同步链路禁止异步化）
- R4: 任务状态枚举含终态集与 checkXxx（照抄 DDD参考代码 OtaUpgradeTaskStatus 模式）
- R5: move_task 历史数据兼容：字段语义、来源标记（manual/rms/charge）不变

## 验收标准

- AC1: nav-task 模块四层结构齐全，platform 中任务相关代码移除
- AC2: TO_POINT/GOAL/FOLLOW_PATH/to_waypoint/FREE 五种任务仿真冒烟全部执行成功
- AC3: 任务取消、看门狗超时收口行为与重构前一致（对照冒烟清单）
- AC4: 充电任务（含 RMS 入口）冒烟通过
- AC5: nav-task 无对 rosbridge 实现类的编译依赖（仅端口接口）
- AC6: `mvnw compile -DskipTests` 通过，fat jar 启动无错

## 范围外

- robot-gateway 模块本体迁移（下一 story）
- 任务表结构变更

## 依赖

../stories/搭建共享内核与用户域试点.md
