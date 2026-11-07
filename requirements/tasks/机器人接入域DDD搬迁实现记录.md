# 机器人接入域 DDD 搬迁实现记录

所属故事: ../stories/迁移机器人接入域到DDD模块.md

## 模块结构（modules/robot-gateway，com.agv.navdeployer.robot）

- modules/robot-core → modules/robot-gateway，artifactId nav-robot-core → nav-robot-gateway，包 sim → robot 三层：
  - domain/：SimAgvTelemetry（遥测聚合）、SimAgvProperties、LocalizationOperation、ArmStateMonitor
  - infrastructure/：RosbridgeClient、RosbridgeHandler、RosbridgeMessageAssembler、RosbridgeMessageLog、RosCommandDispatcher、SimAgvTelemetryCollector、MapSubscriptionGuard、LiveMapCache、LocalizationOperationTracker、NativeMapClient（自 rms 归位）
  - interfaces/：SimAgvSsePublisher
- LiveMapCache/LocalizationOperationTracker 因实现 RosbridgeHandler（协议回调）归 infrastructure 而非 domain——按实现职责而非数据语义归层
- RosbridgeConfig（装配）留 app/config

## 决策记录

- RobotCommandPort/TelemetryPort 完整端口化未在本 story 落地：过渡期 task.application 直接注入 gateway 门面（RosCommandDispatcher/SimAgvTelemetry 等），RosbridgeClient 已全部经 task 的 RobotConnectionPort 隔离；完整指令端口（FollowEdgeGoal/结果 record 等类型的端口化）收益/风险比不佳，作为后续治理项
- SSE/遥测/机械臂链路零改动（仅移动与包名），重连与订阅闸门时序不变

## 验证情况

- `mvnw clean package` 全仓通过；SimAgvProperties 内容与绑定 key 未动
- 待冒烟：AC2（SSE 遥测与 snapshot）、AC3（move/charge/control 链路）、AC4（机械臂）、AC5（断连重连）
