# 实现RMS删除V2地图

状态: 规划中

## 背景与动机

RMS mapping/delete 指令当前只有 V1 分支（findMapByName → 删 nav_map），V2 地图无法经 RMS 删除。V1 下线前需补 V2 分支。

## 角色与意图

As a 云端 RMS，I want 下发 mapping/delete 删除平台上的 V2 地图，So that 云端与现场地图清单保持一致。

## 功能描述

RmsMapService.mapping/delete 增加 V2 分支：按图名匹配 V2 地图 → 校验非当前执行基准 → 删除（含 MinIO 栅格对象）。V1 兜底分支保留至 V1 下线故事移除。

## 业务规则

- R1: 删除前校验地图非当前 PUBLISHED 执行基准，是则拒绝并回错误码
- R2: 删除 V2 地图须同步清理 MinIO v2-maps/ 前缀对象（沿用 V2 删除链路）
- R3: 结果上报语义与现有 RMS 指令一致（result-report）
- R4: 找不到图时行为与现状一致（错误响应）

## 验收标准

- AC1: 云端下发 mapping/delete 指定 V2 图名，地图被删且 RMS 收到成功结果
- AC2: 删除当前执行基准图时被拒绝且地图仍存在
- AC3: 删除后 v2 地图列表不再含该图，MinIO 对应对象已清理

## 范围外

- 批量删除、云端地图清单同步协议扩展

## 依赖

../stories/搭建重构地基.md
