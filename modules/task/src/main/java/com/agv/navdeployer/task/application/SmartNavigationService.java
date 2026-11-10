package com.agv.navdeployer.task.application;

import com.agv.navdeployer.map.application.MapMetaService;
import com.agv.navdeployer.map.application.RoutePlanService;
import com.agv.navdeployer.map.domain.MapMeta;
import com.agv.navdeployer.map.domain.MapWaypoint;
import com.agv.navdeployer.task.application.cmd.MoveTaskCreateCmd;
import com.agv.navdeployer.task.application.dto.MoveTaskDTO;
import com.agv.navdeployer.task.domain.port.RobotTelemetryPort;
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
    private final MapMetaService mapMetaService;
    private final RobotTelemetryPort telemetry;

    public SmartNavigationService(MoveTaskService moveTaskService,
                                  RoutePlanService routePlanService,
                                  MapMetaService mapMetaService,
                                  RobotTelemetryPort telemetry) {
        this.moveTaskService = moveTaskService;
        this.routePlanService = routePlanService;
        this.mapMetaService = mapMetaService;
        this.telemetry = telemetry;
    }

    public MoveTaskDTO createOptimizedMoveTask(String label, double x, double y, double yaw, String source) {
        List<RoutePlanService.RouteStep> steps = planRouteSteps(label, x, y);
        if (!steps.isEmpty()) {
            List<MoveTaskCreateCmd.ChainLeg> legs = steps.stream()
                    .map(s -> new MoveTaskCreateCmd.ChainLeg(s.pathId(), s.fromWaypointCode(), s.toWaypointCode()))
                    .toList();
            return moveTaskService.create(new MoveTaskCreateCmd(
                    "FOLLOW_PATH", null, steps.get(0).pathId(), null, null, null, null, Boolean.TRUE, legs), source);
        }
        return createGoalTask(x, y, yaw, source);
    }

    public List<RoutePlanService.RouteStep> planRouteSteps(String label, double x, double y) {
        try {
            MapMeta publishedMap = mapMetaService.findPublished();
            if (publishedMap == null) {
                return List.of();
            }
            List<MapWaypoint> waypoints = mapMetaService.listWaypoints(publishedMap.getId());
            if (waypoints.isEmpty()) {
                return List.of();
            }
            var targetWp = nearestWaypoint(waypoints, x, y);
            if (targetWp == null || targetWp.distance > WAYPOINT_MATCH_RADIUS) {
                log.debug("smart navigate: target ({},{}) not near any waypoint, fallback GOAL", x, y);
                return List.of();
            }
            var pose = telemetry.mapPose();
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
            List<RoutePlanService.RouteStep> steps = plan.steps().stream()
                    .filter(s -> !s.isCrossFloor() && s.pathId() > 0)
                    .toList();
            if (steps.isEmpty()) {
                return List.of();
            }
            log.info("{} route navigation plan: paths={}, steps={}, totalCost={}s",
                    label, steps.stream().map(RoutePlanService.RouteStep::pathId).distinct().toList(),
                    plan.steps().size(), String.format("%.1f", plan.totalCost()));
            return steps;
        } catch (Exception e) {
            log.debug("route navigation plan failed, fallback GOAL: {}", e.getMessage());
            return List.of();
        }
    }

    public MoveTaskDTO createFollowPathTask(long pathId, String source) {
        return moveTaskService.create(new MoveTaskCreateCmd(
                "FOLLOW_PATH", null, pathId, null, null, null, null, null), source);
    }

    public MoveTaskDTO createGoalTask(double x, double y, double yaw, String source) {
        return moveTaskService.create(new MoveTaskCreateCmd(
                "GOAL", null, null, x, y, yaw, null, Boolean.TRUE), source);
    }

    private record NearestWaypoint(MapWaypoint waypoint, double distance) {
    }

    private static NearestWaypoint nearestWaypoint(List<MapWaypoint> waypoints, double x, double y) {
        MapWaypoint best = null;
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
