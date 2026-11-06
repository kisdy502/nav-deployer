# 迁移RMS对接域到DDD模块

状态: 规划中

## 背景与动机

rms 模块（46 文件）按功能分包，RmsTaskService 827 行混合任务状态管理与协议编排。重组为 nav-rms 四层：RmsTask 充血聚合、协议细节全部压入 infrastructure。

## 角色与意图

As a 开发者，I want RMS 对接域以 DDD 四层独立，So that 云端协议变化不影响任务领域逻辑。

## 功能描述

1. 新建 nav-rms 模块：RmsTask 聚合（状态机、模板、结果上报规则入实体/领域服务）、RmsTaskDomainService（跨实体）、application 编排（对 nav-task/nav-map application 的调用）
2. zenoh 会话、Report/Command/Service Gateway、协议 DTO（command/report/service）全部归 infrastructure
3. 任务派发链路：RMS 指令 → application → nav-task 端口/服务 → 结果回推 RMS
4. 对外行为零变化（RMS 协议报文、心跳、上报字段）

## 业务规则

- R1: domain 零 zenoh import、零协议 DTO 引用（防腐转换在 infrastructure 边界完成）
- R2: 跨 BC 只调 nav-task/nav-map 的 application 或 Repository，禁 Mapper 直连
- R3: 充电模板、质检/示教任务等既有子能力语义不变
- R4: 结果上报（result-report）时序与重试语义不变

## 验收标准

- AC1: nav-rms 四层齐全，protocol/gateway 代码全部位于 infrastructure
- AC2: RMS 注册/心跳/状态上报冒烟通过（云端模拟器或联调环境）
- AC3: 云端派发导航任务 → 机器人执行 → result-report 收到成功结果
- AC4: 充电任务模板派发冒烟通过
- AC5: domain 包 grep 无 zenoh、无 protocol DTO 引用
- AC6: `mvnw compile -DskipTests` 通过

## 范围外

- RMS 协议扩展（新指令类型）
- zenoh 版本升级

## 依赖

../stories/迁移任务域到DDD模块.md
