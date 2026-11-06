package com.agv.navdeployer.exchange;

import com.agv.navdeployer.vo.MapGridVO;
import com.agv.navdeployer.vo.NavPointVO;
import com.agv.navdeployer.vo.NavPathVO;
import com.agv.navdeployer.vo.PathEdgeVO;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ScheduleMapAdapter {

    private static final double DEFAULT_ROUTE_SPEED = 0.6;
    private static final int GOAL_TYPE_PATH_MARKER = 0;
    private static final int GOAL_TYPE_WORKSTATION = 1;
    private static final int GOAL_TYPE_CHARGING = 2;
    private static final int GOAL_TYPE_REST = 3;
    private static final String DEFAULT_GOAL_ATTRIBUTES =
            "{\"quadrant_divide_angle\":\"0,90,180,270\"}";
    private static final String DEFAULT_ROUTE_ATTRIBUTES =
            "{\"shelf_posture\":0,\"path_width\":100,\"cost\":1,\"empty_cost\":1,\"full_cost\":1}";

    public byte[] buildLxmap(MapGridVO grid, List<NavPointVO> points, List<NavPathVO> paths,
                            Map<Long, List<PathEdgeVO>> edgesByPath, boolean forceBidirectional) {
        StringBuilder sb = new StringBuilder();
        sb.append("Topology-Map\n");
        sb.append("MapId: 0\n");
        sb.append("Rect: ").append(grid.getOriginX()).append(' ').append(grid.getOriginY())
                .append(' ').append(grid.getWidth() * grid.getResolution())
                .append(' ').append(grid.getHeight() * grid.getResolution()).append('\n');
        sb.append("Version: \n");
        sb.append("Description: default description\n");

        Map<Long, Integer> pointIds = new LinkedHashMap<>();
        int nextGoalId = 1;
        for (NavPointVO point : points) {
            pointIds.put(point.getId(), nextGoalId);
            sb.append("Cairn: Goal ").append(nextGoalId++)
                    .append(' ').append(point.getPointCode())
                    .append(" \"\" \"\" ")
                    .append(point.getX()).append(' ').append(point.getY()).append(' ').append(point.getYaw())
                    .append(' ').append(scheduleGoalType(point.getPointType()))
                    .append(" 0 7 0 \"\" \"0,0,0\" \"0,0,0\" \"0\" \"1\" 0 0 0 0 0 0 ")
                    .append(DEFAULT_GOAL_ATTRIBUTES).append('\n');
        }
        // 调度服务器把每行 Route 当作单向边。旧版按无向点对去重并补反向边；
        // V2 已按路线方向提供边，只按有向点对去重。
        int nextRouteId = 1;
        Set<String> emittedConnections = new LinkedHashSet<>();
        for (NavPathVO path : paths) {
            List<PathEdgeVO> edges = edgesByPath.getOrDefault(path.getId(), List.of());
            for (PathEdgeVO edge : edges) {
                Integer src = pointIds.get(edge.getSourcePointId());
                Integer dst = pointIds.get(edge.getTargetPointId());
                NavPointVO source = findPoint(points, edge.getSourcePointId());
                NavPointVO target = findPoint(points, edge.getTargetPointId());
                if (src == null || dst == null || source == null || target == null) {
                    throw new IllegalArgumentException("路线边引用了当前地图不存在的点位，拒绝生成残缺地图: path="
                            + path.getId() + ", edge=" + edge.getId() + ", source="
                            + edge.getSourcePointId() + ", target=" + edge.getTargetPointId());
                }
                String connectionKey = forceBidirectional
                        ? Math.min(src, dst) + ":" + Math.max(src, dst) : src + ":" + dst;
                if (!emittedConnections.add(connectionKey)) {
                    continue;
                }
                double speed = edge.getMaxSpeed() != null ? edge.getMaxSpeed() : DEFAULT_ROUTE_SPEED;
                boolean reverseDriving = Boolean.TRUE.equals(edge.getReverse());
                nextRouteId = appendRoute(sb, nextRouteId, src, dst, source, target, speed, reverseDriving);
                if (forceBidirectional && !src.equals(dst)) {
                    nextRouteId = appendRoute(sb, nextRouteId, dst, src, target, source, speed, reverseDriving);
                }
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static int appendRoute(StringBuilder sb,
                                   int routeId,
                                   int src,
                                   int dst,
                                   NavPointVO source,
                                   NavPointVO target,
                                   double speed,
                                   boolean reverseDriving) {
        sb.append("Cairn: Route ").append(routeId)
                .append(' ').append(src).append(' ').append(dst)
                .append(' ').append(source.getX()).append(' ').append(source.getY())
                .append(' ').append(target.getX()).append(' ').append(target.getY())
                .append(' ').append(reverseDriving ? 1 : 0)
                .append(' ').append(speed)
                .append(" 0 1 0 0 0 \"\" \"0\" \"1\" \"\" \"0\" 0 0 0 ")
                .append(DEFAULT_ROUTE_ATTRIBUTES).append('\n');
        return routeId + 1;
    }

    private static int scheduleGoalType(String pointType) {
        if (pointType == null || pointType.isBlank() || "NORMAL".equalsIgnoreCase(pointType)) {
            return GOAL_TYPE_PATH_MARKER;
        }
        if ("CHARGER".equalsIgnoreCase(pointType)) {
            return GOAL_TYPE_CHARGING;
        }
        if ("WORK".equalsIgnoreCase(pointType)) {
            return GOAL_TYPE_WORKSTATION;
        }
        if ("HOME".equalsIgnoreCase(pointType)) {
            return GOAL_TYPE_REST;
        }
        throw new IllegalArgumentException("不支持的点位类型，无法生成调度地图: " + pointType);
    }

    private static NavPointVO findPoint(List<NavPointVO> points, Long id) {
        return points.stream().filter(p -> p.getId().equals(id)).findFirst().orElse(null);
    }
}
