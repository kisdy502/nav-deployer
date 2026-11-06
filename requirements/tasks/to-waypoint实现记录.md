# to_waypoint 实现记录

所属故事: ../stories/新增按V2点位导航.md

## 改动清单

后端：
- V6__move_task_waypoint.sql：move_task 新增 waypoint_id 列
- MoveTask：TYPE_TO_WAYPOINT 常量 + waypointId 字段
- MoveTaskCreateDTO：waypoint_id 入参（task_type 正则扩展）
- MoveTaskVO：waypoint_id 出参
- MoveTaskService：TO_WAYPOINT 分支（校验点位属于当前 PUBLISHED 地图，坐标创建时快照，直线段执行）
- SmartNavigationService：两处位置参数构造适配新 record 组件
- MoveTaskController：@Operation 说明更新

前端：
- types/api.ts：MoveTaskType 扩展、MoveTaskVO/MoveTaskCreateDTO 增 waypoint_id
- tasks.ts：navigateToWaypoint()
- MapCanvas2.vue：点位右键"直线导航"改走 TO_WAYPOINT
- format.ts：任务类型标签映射补 TO_WAYPOINT

## 验证情况

- `mvnw compile -DskipTests` 通过；`npm run build`（vue-tsc + vite）通过
- 待仿真环境冒烟：AC1（to_waypoint 执行到达）、AC2（非 PUBLISHED 地图点位拒绝）、AC3（v2 编辑器右键生效）、AC4（TO_POINT 共存不受影响）
