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

@Component
public class SmartNavigationService {

    private static final Logger log = LoggerFactory.getLogger(SmartNavigationService.class);
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

    public MoveTaskVO createOptimizedMoveTask(String label, double x, double y, double yaw, String source) {
        List<Long> pathIds = planRoutePathIds(label, x, y);
        if (!pathIds.isEmpty()) {
            return createFollowPathTask(pathIds.get(0), source);
        }
        return createGoalTask(x, y, yaw, source);
    }

    public List<Long> planRoutePathIds(String label, double x, double y) {
        try {
            var publishedMap = v2MapMapper.selectOne(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(
                                    com.agv.navdeployer.metadata.entity.MapMeta.class)
                            .eq(com.agv.navdeployer.metadata.entity.MapMeta::getStatus,
                                    com.agv.navdeployer.metadata.entity.MapMeta.STATUS_PUBLISHED)
                            .last("LIMIT 1"));
            if (publishedMap == null) {
                return List.of();
            }
            var waypoints = v2WaypointMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(
                                    com.agv.navdeployer.metadata.entity.MapWaypoint.class)
                            .eq(com.agv.navdeployer.metadata.entity.MapWaypoint::getMapId,
                                    publishedMap.getId()));
            if (waypoints.isEmpty()) {
                return List.of();
            }
            var targetWp = nearestWaypoint(waypoints, x, y);
            if (targetWp == null || targetWp.distance > WAYPOINT_MATCH_RADIUS) {
                log.debug("smart navigate: target ({},{}) not near any waypoint, fallback GOAL", x, y);
                return List.of();
            }
            var pose = telemetry.getMapPose();
            if (pose == null) {
                return List.of();
            }
            var robotWp = nearestWaypoint(waypoints, pose.x(), pose.y());
            if (robotWp == null || robotWp.distance > WAYPOINT_MATCH_RADIUS * 2) {
                return List.of();
            }
            var plan = routePlanService.plan(publishedMap.getId(), robotWp.waypoint.getId(), targetWp.waypoint.getId());
            if (!plan.found() || plan.steps().isEmpty()) {
                log.debug("smart navigate: no route from {} to {}, fallback GOAL",
                        robotWp.waypoint.getWaypointCode(), targetWp.waypoint.getWaypointCode());
                return List.of();
            }
            var pathIds = plan.steps().stream()
                    .filter(s -> !s.isCrossFloor() && s.pathId() > 0)
                    .map(RoutePlanService.RouteStep::pathId)
                    .distinct()
                    .toList();
            if (pathIds.isEmpty()) {
                return List.of();
            }
            log.info("{} route navigation plan: paths={}, steps={}, totalCost={}s",
                    label, pathIds, plan.steps().size(), String.format("%.1f", plan.totalCost()));
            return pathIds;
        } catch (Exception e) {
            log.debug("route navigation plan failed, fallback GOAL: {}", e.getMessage());
            return List.of();
        }
    }

    public MoveTaskVO createFollowPathTask(long pathId, String source) {
        return moveTaskService.create(new MoveTaskCreateDTO(
                "FOLLOW_PATH", null, null, pathId, null, null, null, null, null), source);
    }

    public MoveTaskVO createGoalTask(double x, double y, double yaw, String source) {
        return moveTaskService.create(new MoveTaskCreateDTO(
                "GOAL", null, null, null, x, y, yaw, null, Boolean.TRUE), source);
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
