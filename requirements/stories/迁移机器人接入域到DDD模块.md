# 迁移机器人接入域到DDD模块

状态: 规划中

## 背景与动机

robot-core（rosbridge 客户端、遥测采集 809 行、指令分发、SSE、机械臂监控、LiveMapCache）重组为 nav-robot-gateway，实现 nav-task/nav-map 定义的端口（RobotCommandPort、LiveGridPort 等），完成纯依赖倒置。

## 角色与意图

As a 开发者，I want 机器人接入作为基础设施模块实现各 BC 端口，So that 业务域不感知 rosbridge 协议细节。

## 功能描述

1. 新建 nav-robot-gateway 模块：rosbridge 客户端/消息装配/指令分发归 infrastructure；遥测采集与聚合、机械臂状态监控为 domain（连接状态、遥测值对象）；SSE 推送为 interfaces
2. 实现 nav-task 的 RobotCommandPort/TelemetryPort、nav-map 的 LiveGridPort
3. SimAgvProperties（528 行）原样迁入 infrastructure/config，不拆分
4. 机器人控制类端点（/robot/*、SSE /sse/agv）保持 URL 与响应零变化

## 业务规则

- R1: 模块依赖方向 nav-robot-gateway → nav-task/nav-map（实现端口），禁止反向
- R2: 重连、订阅闸门、消息装配时序行为不变（协议层零改动，仅移动与接口化）
- R3: SimAgvProperties 内容与绑定 key 不变
- R4: SSE 事件流字段与频率不变（前端零改动）

## 验收标准

- AC1: nav-robot-gateway 实现全部端口接口，无 BC 反向依赖（编译验证）
- AC2: 机器人连接后 SSE 遥测流正常、/robot/snapshot 响应与重构前一致
- AC3: 下发 move/charge/control 指令链路冒烟通过（经任务域端口）
- AC4: 机械臂状态监控 SSE 冒烟通过
- AC5: 断连重连场景服务不崩溃、恢复后遥测继续
- AC6: `mvnw compile -DskipTests` 通过

## 范围外

- rosbridge 协议升级（agv_bridge_v3 等）
- SimAgvProperties 拆分优化（后续补）

## 依赖

../stories/迁移任务域到DDD模块.md、../stories/迁移地图域到DDD模块.md
