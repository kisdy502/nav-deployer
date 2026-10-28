package com.agv.navdeployer.service;

import com.agv.navdeployer.dto.MoveTaskCreateDTO;
import com.agv.navdeployer.metadata.mapper.MapMetaMapper;
import com.agv.navdeployer.metadata.mapper.MapWaypointMapper;
import com.agv.navdeployer.metadata.service.RoutePlanService;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import com.agv.navdeployer.vo.MoveTaskVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 智能导航：调用方只给目标坐标，上位机自主选择最优执行方式。
 * 优先尝试路线导航（V2 已部署路线图 Dijkstra 规划，走曲线），不可达回落直线 GOAL。
 * 源自 RmsTaskService（RMS 任务导航），现为 RMS 任务 / 充电任务共用的导航入口。
 */
@Component
public class SmartNavigationService {

    private static final Logger log = LoggerFactory.getLogger(SmartNavigationService.class);
    /** 目标点位与机器人最近点位之间的匹配距离阈值（米） */
    private static final double WAYPOINT_MATCH_RADIUS = 1.5;

    private final MoveTaskService moveTaskService;
    private final RoutePlanService routePlanService;
    private final MapMetaMapper v2MapMapper;
    private final MapWaypointMapper v2WaypointMapper;
    private final SimAgvTelemetry telemetry;

    public SmartNavigationService(MoveTaskService moveTaskService,
                                  RoutePlanService routePlanService,
                                  MapMetaMapper v2MapMapper,
                                  MapWaypointMapper v2WaypointMapper,
                                  SimAgvTelemetry telemetry) {
        this.moveTaskService = moveTaskService;
        this.routePlanService = routePlanService;
        this.v2MapMapper = v2MapMapper;
        this.v2WaypointMapper = v2WaypointMapper;
        this.telemetry = telemetry;
    }

    /**
     * 智能导航到目标位姿：优先 FOLLOW_PATH（Dijkstra 路线首段），不可达回落 GOAL 直线。
     * 注意：路线含多条 path 时只执行第一条（RMS navigate 语义，多段中转由前端触发）；
     * 需要走完整条链的调用方（如充电）应使用 {@link #planRoutePathIds} 自行逐段串联。
     * 闸门（连接/定位/模式/单任务互斥/机械臂归位）由 MoveTaskService.create 统一校验。
     *
     * @param label 日志标签（如 rms 任务 id / charge）
     */
    public MoveTaskVO createOptimizedMoveTask(String label, double x, double y, double yaw) {
        List<Long> pathIds = planRoutePathIds(label, x, y);
        if (!pathIds.isEmpty()) {
            return createFollowPathTask(pathIds.get(0));
        }
        return createGoalTask(x, y, yaw);
    }

    /**
     * 规划到目标的路线 path id 列表（Dijkstra，去重、去跨楼层，有序完整链）。
     * 空列表 = 无可走路线（目标/机器人不在点位附近或图不通），调用方应回落直线。
     */
    public List<Long> planRoutePathIds(String label, double x, double y) {
        try {
            // 当前 PUBLISHED 的 V2 地图
            var publishedMap = v2MapMapper.selectOne(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(
                                    com.agv.navdeployer.metadata.entity.MapMeta.class)
                            .eq(com.agv.navdeployer.metadata.entity.MapMeta::getStatus,
                                    com.agv.navdeployer.metadata.entity.MapMeta.STATUS_PUBLISHED)
                            .last("LIMIT 1"));
            if (publishedMap == null) {
                return List.of();
            }
            // 加载该地图全部点位
            var waypoints = v2WaypointMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(
                                    com.agv.navdeployer.metadata.entity.MapWaypoint.class)
                            .eq(com.agv.navdeployer.metadata.entity.MapWaypoint::getMapId,
                                    publishedMap.getId()));
            if (waypoints.isEmpty()) {
                return List.of();
            }
            // 目标最近点位
            var targetWp = nearestWaypoint(waypoints, x, y);
            if (targetWp == null || targetWp.distance > WAYPOINT_MATCH_RADIUS) {
                log.debug("smart navigate: target ({},{}) not near any waypoint, fallback GOAL", x, y);
                return List.of();
            }
            // 机器人当前位姿 → 最近点位
            var pose = telemetry.getMapPose();
            if (pose == null) {
                return List.of();
            }
            var robotWp = nearestWaypoint(waypoints, pose.x(), pose.y());
            if (robotWp == null || robotWp.distance > WAYPOINT_MATCH_RADIUS * 2) {
                return List.of(); // 机器人不在任何点位附近，无法路线导航
            }
            // Dijkstra 路径规划
            var plan = routePlanService.plan(publishedMap.getId(), robotWp.waypoint.getId(), targetWp.waypoint.getId());
            if (!plan.found() || plan.steps().isEmpty()) {
                log.debug("smart navigate: no route from {} to {}, fallback GOAL",
                        robotWp.waypoint.getWaypointCode(), targetWp.waypoint.getWaypointCode());
                return List.of();
            }
            // 提取不重复的路线 ID
            var pathIds = plan.steps().stream()
                    .filter(s -> !s.isCrossFloor() && s.pathId() > 0)
                    .map(RoutePlanService.RouteStep::pathId)
                    .distinct()
                    .toList();
            if (pathIds.isEmpty()) {
                return List.of();
            }
            // 单条或多条路线都要走完整条链：返回有序完整列表，由调用方逐段串联执行
            log.info("{} route navigation plan: paths={}, steps={}, totalCost={}s",
                    label, pathIds, plan.steps().size(), String.format("%.1f", plan.totalCost()));
            return pathIds;
        } catch (Exception e) {
            log.debug("route navigation plan failed, fallback GOAL: {}", e.getMessage());
            return List.of();
        }
    }

    /** 按路线（FOLLOW_PATH）创建移动任务：单条 path 内部多段由 MoveTaskService 串行执行。 */
    public MoveTaskVO createFollowPathTask(long pathId) {
        return moveTaskService.create(new MoveTaskCreateDTO(
                "FOLLOW_PATH", null, pathId, null, null, null, null, null));
    }

    /** 直线导航兜底（GOAL，到点旋转对准 theta）。 */
    public MoveTaskVO createGoalTask(double x, double y, double yaw) {
        return moveTaskService.create(new MoveTaskCreateDTO(
                "GOAL", null, null, x, y, yaw, null, Boolean.TRUE));
    }

    private record NearestWaypoint(com.agv.navdeployer.metadata.entity.MapWaypoint waypoint, double distance) {
    }

    private static NearestWaypoint nearestWaypoint(
            List<com.agv.navdeployer.metadata.entity.MapWaypoint> waypoints, double x, double y) {
        com.agv.navdeployer.metadata.entity.MapWaypoint best = null;
        double bestDist = Double.MAX_VALUE;
        for (var wp : waypoints) {
            double dist = Math.hypot(wp.getX() - x, wp.getY() - y);
            if (dist < bestDist) {
                best = wp;
                bestDist = dist;
            }
        }
        return best != null ? new NearestWaypoint(best, bestDist) : null;
    }
}
