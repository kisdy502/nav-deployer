# 迁移地图域到DDD模块

状态: 测试中

## 背景与动机

nav-map 承载 V2 十表模型（MapMetaService 538 行）、地图包交换（MapPackageService 750 行、LegacyTopologyExporter、.lxmap 投影）、楼层/点位/区域/货架/路线 CRUD 与 Dijkstra 规划。按模板结构化并定义 LiveGridPort。

## 角色与意图

As a 开发者，I want 地图域独立成 DDD 模块，So that 多楼层地图模型、发布流程与包交换规则内聚。

## 功能描述

1. 新建 nav-map 模块：Map 聚合（含发布状态机：DRAFT→PUBLISHED，发布校验入实体）、Floor/Waypoint/Zone/Shelf/Path/Segment/InterFloorConn 实体与值对象（11 类点位词表、贝塞尔段）、MapRepository 等
2. 地图包交换（exportZip/importZip/.lxmap 投影）归 application 编排 + domain 投影逻辑
3. domain 定义 LiveGridPort（实时栅格读取），由 nav-robot-gateway 实现
4. 路径规划（Dijkstra）定位为领域服务
5. 对外 V2 API 零变化，控制器迁入 interfaces 层并补 Swagger

## 业务规则

- R1: 发布校验（拓扑完整、必经点、PUBLISHED 后任务闸门）为领域行为，禁在编排层 if
- R2: MinIO 栅格存取为 infrastructure 实现（GridObjectStore 归位）
- R3: .lxmap 投影数据源仅 V2（V1 已下线）
- R4: 楼层内坐标/跨楼层连接的校验规则入值对象
- R5: 十张表实体结构差异大者才引入 DO + Mapstruct（按 AGENTS.md 裁决）

## 验收标准

- AC1: nav-map 四层结构齐全，metadata 系代码全部迁入
- AC2: v2 编辑器全量树加载、楼层/点位/区域/货架/路线 CRUD、发布流程冒烟通过
- AC3: 地图包导出 zip 结构与重构前一致（含 .lxmap，二进制比对栅格文件）
- AC4: import-zip 导入还原冒烟通过
- AC5: 路径规划 /api/v2/route/plan 结果与重构前一致（同输入同输出）
- AC6: `mvnw compile -DskipTests` 通过

## 范围外

- 前端编辑器改版
- 地图表结构变更

## 依赖

../stories/搭建共享内核与用户域试点.md、../stories/迁移任务域到DDD模块.md
