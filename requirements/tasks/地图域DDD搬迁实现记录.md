# 地图域 DDD 搬迁实现记录

所属故事: ../stories/迁移地图域到DDD模块.md

## 模块结构（modules/map，com.agv.navdeployer.map）

- interfaces/：FloorV2/WaypointV2/ZoneV2/ShelfV2/PathV2/InterFloorConnV2/RoutePlan 七控制器迁入；MapV2Controller 留 app 装配层（switch 端点编排 task 域，避免 map↔task 循环依赖——决策记录）
- application/：MapMetaService、MapFloorService、MapWaypointService、MapZoneService、MapShelfService、MapPathService、MapConnService、RoutePlanService（Dijkstra 领域服务）、GridObjectStore、MapVersionTicker、MapPackageService、MetadataJson
  - cmd/：入参 XxxCmd（Create/Update/Save/Publish 系 + 嵌套载荷 ShelfEdge/ShelfLayer/ShelfSlot/ConnEndpoint/ConnRampInfo/RampInfo/PathSegment Cmd）
  - dto/：出参 XxxDTO（原 16 VO 统一改名）
- domain/：MapMeta、MapFloor、MapWaypoint、MapZone、MapShelf、MapShelfLayer、MapShelfSlot、MapPath、MapPathSegment、MapInterFloorConn 十实体
- infrastructure/：repository/mapper 十 Mapper + exchange/（ScheduleMapAdapter、LegacyTopologyExporter、PgmImageUtil、NavPointVO/NavPathVO/PathEdgeVO 投影结构、format/ 13 包文件记录）

## 联动改动

- NotFoundException 统一迁 nav-common（common.error），platform/app 双份删除
- NativeMapClient 归位 robot-core（机器人原生地图传输，历史误居 rms/map；map 与 rms 均合法依赖 gateway）
- task/rms/app pom 依赖切换；task 由 platform 改依赖 map
- exchange 层（.lxmap 投影）完整保留，导出链路零变化

## 决策记录

- Repository 化未全覆盖：metadata 各 Service 直携 mapper 为过渡形态；跨 BC 访问（task）走 map.application（MapMetaService），符合"跨 BC 走对方 application"规则；Repository 收敛随跨域调用治理另行安排
- 入参与出参撞名（ShelfEdgeDTO 等 7 组）以嵌套载荷改 Cmd 后缀消解
- MapV2Controller 留 app：switch 端点依赖 task.application（切图编排），装配层持双依赖，URL /api/v2/maps/* 不变

## 验证情况

- `mvnw clean compile` 全仓通过
- 待冒烟：AC2（编辑器全量树/CRUD/发布）、AC3/AC4（地图包导出导入比对）、AC5（路径规划）、AC6
