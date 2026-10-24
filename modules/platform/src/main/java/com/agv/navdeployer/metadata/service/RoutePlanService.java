package com.agv.navdeployer.metadata.service;

import com.agv.navdeployer.metadata.entity.MapInterFloorConn;
import com.agv.navdeployer.metadata.entity.MapPath;
import com.agv.navdeployer.metadata.entity.MapPathSegment;
import com.agv.navdeployer.metadata.entity.MapWaypoint;
import com.agv.navdeployer.metadata.entity.MapZone;
import com.agv.navdeployer.metadata.mapper.MapInterFloorConnMapper;
import com.agv.navdeployer.metadata.mapper.MapPathMapper;
import com.agv.navdeployer.metadata.mapper.MapPathSegmentMapper;
import com.agv.navdeployer.metadata.mapper.MapWaypointMapper;
import com.agv.navdeployer.metadata.mapper.MapZoneMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * V2 路径规划：Dijkstra 加权最短路径。
 *
 * <p>图模型：
 * <ul>
 *   <li>节点 = 点位（跨楼层全图）</li>
 *   <li>边 = 已部署路线段（同楼层），权重 = 段长度 / 段限速（时间代价）</li>
 *   <li>跨楼层边 = 楼层间连接（电梯/楼梯/坡道），权重 = 运输时间估计</li>
 *   <li>禁行区约束：路径段穿过禁行区 → 代价 = ∞（跳过）</li>
 *   <li>减速区：路径段穿过减速区 → 权重 × 减速系数</li>
 * </ul>
 */
@Service
public class RoutePlanService {

    private static final Logger log = LoggerFactory.getLogger(RoutePlanService.class);

    /** 减速区代价系数（限速越低惩罚越大） */
    private static final double SLOW_ZONE_FACTOR = 2.0;
    /** 楼层间连接默认运输时间（秒）：电梯 ~30s、楼梯 ~60s、坡道 ~20s */
    private static final Map<String, Double> CONN_BASE_TIME = Map.of(
            "elevator", 30.0, "stairs", 60.0, "ramp", 20.0);

    private final MapPathMapper pathMapper;
    private final MapPathSegmentMapper segmentMapper;
    private final MapWaypointMapper waypointMapper;
    private final MapZoneMapper zoneMapper;
    private final MapInterFloorConnMapper connMapper;

    public RoutePlanService(MapPathMapper pathMapper,
                            MapPathSegmentMapper segmentMapper,
                            MapWaypointMapper waypointMapper,
                            MapZoneMapper zoneMapper,
                            MapInterFloorConnMapper connMapper) {
        this.pathMapper = pathMapper;
        this.segmentMapper = segmentMapper;
        this.waypointMapper = waypointMapper;
        this.zoneMapper = zoneMapper;
        this.connMapper = connMapper;
    }

    /** 规划结果：路线序列 + 预计总时间 */
    public record RouteStep(
            long pathId,
            String pathCode,
            long fromWaypointId,
            long toWaypointId,
            String fromWaypointCode,
            String toWaypointCode,
            Long floorId,
            boolean isCrossFloor,
            String connType,
            double cost) {
    }

    public record RoutePlan(
            List<RouteStep> steps,
            double totalCost,
            int crossFloorCount,
            boolean found) {

        static RoutePlan notFound() {
            return new RoutePlan(List.of(), 0, 0, false);
        }
    }

    /** Dijkstra 图节点 */
    private record Node(long waypointId, double cost, List<RouteStep> steps) implements Comparable<Node> {
        @Override
        public int compareTo(Node other) {
            return Double.compare(cost, other.cost);
        }
    }

    /** 图边 */
    private record GraphEdge(
            long neighborId,
            double cost,
            Long pathId,
            String pathCode,
            boolean isCrossFloor,
            String connType) {
    }

    /**
     * 规划从 fromWaypointId 到 toWaypointId 的最优路径。
     * 支持跨楼层（通过楼层间连接）、禁行区避让、减速区惩罚。
     */
    public RoutePlan plan(Long mapId, Long fromWaypointId, Long toWaypointId) {
        // 加载全图数据
        Map<Long, MapWaypoint> waypoints = loadWaypoints(mapId);
        Map<Long, MapPath> paths = loadDeployedPaths(mapId);
        Map<Long, List<MapPathSegment>> segmentsByPath = loadSegments(paths.keySet());
        List<MapZone> zones = zoneMapper.selectList(Wrappers.lambdaQuery(MapZone.class)
                .eq(MapZone::getMapId, mapId));
        List<MapInterFloorConn> conns = connMapper.selectList(Wrappers.lambdaQuery(MapInterFloorConn.class)
                .eq(MapInterFloorConn::getMapId, mapId));

        // 构建邻接图
        Map<Long, List<GraphEdge>> graph = buildGraph(waypoints, paths, segmentsByPath, zones, conns);

        // Dijkstra
        Map<Long, Double> dist = new HashMap<>();
        Map<Long, List<RouteStep>> bestSteps = new HashMap<>();
        PriorityQueue<Node> pq = new PriorityQueue<>();
        Set<Long> settled = new HashSet<>();

        pq.offer(new Node(fromWaypointId, 0, List.of()));
        dist.put(fromWaypointId, 0.0);

        while (!pq.isEmpty()) {
            Node current = pq.poll();
            if (settled.contains(current.waypointId())) {
                continue;
            }
            settled.add(current.waypointId());
            if (current.waypointId() == toWaypointId) {
                int crossFloor = (int) current.steps().stream().filter(RouteStep::isCrossFloor).count();
                return new RoutePlan(current.steps(), current.cost(), crossFloor, true);
            }
            for (GraphEdge edge : graph.getOrDefault(current.waypointId(), List.of())) {
                if (settled.contains(edge.neighborId())) {
                    continue;
                }
                double newCost = current.cost() + edge.cost();
                if (newCost < dist.getOrDefault(edge.neighborId(), Double.MAX_VALUE)) {
                    dist.put(edge.neighborId(), newCost);
                    RouteStep step = toStep(edge, current.waypointId(), waypoints);
                    List<RouteStep> newSteps = new ArrayList<>(current.steps());
                    newSteps.add(step);
                    pq.offer(new Node(edge.neighborId(), newCost, newSteps));
                }
            }
        }
        log.info("route plan not found: mapId={}, from={}, to={}", mapId, fromWaypointId, toWaypointId);
        return RoutePlan.notFound();
    }

    // ==================== 图构建 ====================

    private Map<Long, List<GraphEdge>> buildGraph(
            Map<Long, MapWaypoint> waypoints,
            Map<Long, MapPath> paths,
            Map<Long, List<MapPathSegment>> segmentsByPath,
            List<MapZone> zones,
            List<MapInterFloorConn> conns) {

        Map<Long, List<GraphEdge>> graph = new HashMap<>();

        // 同楼层路线边
        for (var entry : segmentsByPath.entrySet()) {
            Long pathId = entry.getKey();
            MapPath path = paths.get(pathId);
            List<MapPathSegment> segments = entry.getValue();
            for (MapPathSegment segment : segments) {
                double cost = segmentCost(segment, waypoints, zones, path);
                if (cost >= Double.MAX_VALUE) {
                    continue; // 禁行区：跳过此边
                }
                addEdge(graph, segment.getSourceWaypointId(), segment.getTargetWaypointId(),
                        cost, pathId, path.getPathCode(), false, null);
                if (Boolean.TRUE.equals(path.getIsBidirectional())) {
                    addEdge(graph, segment.getTargetWaypointId(), segment.getSourceWaypointId(),
                            cost, pathId, path.getPathCode(), false, null);
                }
            }
        }

        // 跨楼层连接边（楼层间连接表）
        for (MapInterFloorConn conn : conns) {
            double cost = connCost(conn);
            List<com.agv.navdeployer.metadata.vo.ConnEndpointVO> endpoints = parseEndpoints(conn.getConnections());
            if (endpoints == null || endpoints.size() < 2) {
                continue;
            }
            // 连接的多个端点两两互连（电梯多层停靠）
            for (int i = 0; i < endpoints.size(); i++) {
                for (int j = i + 1; j < endpoints.size(); j++) {
                    Long from = findWaypointIdByCode(waypoints, endpoints.get(i).waypointId());
                    Long to = findWaypointIdByCode(waypoints, endpoints.get(j).waypointId());
                    if (from != null && to != null) {
                        addEdge(graph, from, to, cost, null, conn.getConnCode(), true, conn.getConnType());
                        addEdge(graph, to, from, cost, null, conn.getConnCode(), true, conn.getConnType());
                    }
                }
            }
        }
        return graph;
    }

    /** 段代价：长度 / 限速 × 区域惩罚系数；穿过禁行区返回 ∞ */
    private double segmentCost(MapPathSegment segment,
                               Map<Long, MapWaypoint> waypoints,
                               List<MapZone> zones,
                               MapPath path) {
        MapWaypoint source = waypoints.get(segment.getSourceWaypointId());
        MapWaypoint target = waypoints.get(segment.getTargetWaypointId());
        if (source == null || target == null) {
            return Double.MAX_VALUE;
        }
        // 段长度：贝塞尔曲线用控制点近似弧长，直线用欧氏距离
        double length = segmentLength(segment, source, target);
        // 限速：段级 > 路线级 > 楼层默认（取不到按 0.5 m/s）
        double speed = segment.getMaxSpeed() != null ? segment.getMaxSpeed()
                : path.getMaxSpeed() != null ? path.getMaxSpeed() : 0.5;
        double cost = length / speed;

        // 区域约束：检查段中点是否在区域内
        double midX = (source.getX() + target.getX()) / 2;
        double midY = (source.getY() + target.getY()) / 2;
        for (MapZone zone : zones) {
            if (pointInZone(midX, midY, zone, source.getFloorId())) {
                if ("forbidden".equals(zone.getZoneType())) {
                    return Double.MAX_VALUE; // 禁行区
                }
                if ("slowDown".equals(zone.getZoneType())) {
                    cost *= SLOW_ZONE_FACTOR; // 减速区惩罚
                }
            }
        }
        return cost;
    }

    /** 段长度估计：直线 = 欧氏距离；曲线 = 贝塞尔采样弧长 */
    private double segmentLength(MapPathSegment segment, MapWaypoint source, MapWaypoint target) {
        if (!"CURVE".equals(segment.getSegmentType()) || segment.getControlPoints() == null) {
            return Math.hypot(target.getX() - source.getX(), target.getY() - source.getY());
        }
        // 贝塞尔采样弧长
        try {
            var cps = com.agv.navdeployer.metadata.MetadataJson.MAPPER.readValue(
                    segment.getControlPoints(), double[][].class);
            double length = 0;
            int samples = 20;
            double[] prev = {source.getX(), source.getY()};
            for (int i = 1; i <= samples; i++) {
                double t = (double) i / samples;
                double[] point = bezierPoint(t, source, target, cps);
                length += Math.hypot(point[0] - prev[0], point[1] - prev[1]);
                prev = point;
            }
            return length;
        } catch (Exception e) {
            return Math.hypot(target.getX() - source.getX(), target.getY() - source.getY());
        }
    }

    /** 贝塞尔曲线在参数 t 处的点（支持 1~2 个控制点） */
    private static double[] bezierPoint(double t, MapWaypoint p0, MapWaypoint p1, double[][] cps) {
        double mt = 1 - t;
        if (cps.length == 1) {
            return new double[]{
                    mt * mt * p0.getX() + 2 * mt * t * cps[0][0] + t * t * p1.getX(),
                    mt * mt * p0.getY() + 2 * mt * t * cps[0][1] + t * t * p1.getY()};
        }
        double[] c1 = cps[0];
        double[] c2 = cps.length > 1 ? cps[1] : cps[0];
        return new double[]{
                mt * mt * mt * p0.getX() + 3 * mt * mt * t * c1[0] + 3 * mt * t * t * c2[0] + t * t * t * p1.getX(),
                mt * mt * mt * p0.getY() + 3 * mt * mt * t * c1[1] + 3 * mt * t * t * c2[1] + t * t * t * p1.getY()};
    }

    /** 楼层间连接代价：基础运输时间 + 等待 */
    private double connCost(MapInterFloorConn conn) {
        double base = CONN_BASE_TIME.getOrDefault(conn.getConnType(), 30.0);
        return conn.getMaxSpeed() != null ? base : base;
    }

    // ==================== 工具 ====================

    private void addEdge(Map<Long, List<GraphEdge>> graph, long from, long to,
                         double cost, Long pathId, String pathCode,
                         boolean crossFloor, String connType) {
        graph.computeIfAbsent(from, k -> new ArrayList<>())
                .add(new GraphEdge(to, cost, pathId, pathCode, crossFloor, connType));
    }

    private RouteStep toStep(GraphEdge edge, long fromId, Map<Long, MapWaypoint> waypoints) {
        MapWaypoint from = waypoints.get(fromId);
        MapWaypoint to = waypoints.get((long) edge.neighborId());
        return new RouteStep(
                edge.pathId() != null ? edge.pathId() : 0,
                edge.pathCode(),
                fromId,
                edge.neighborId(),
                from != null ? from.getWaypointCode() : String.valueOf(fromId),
                to != null ? to.getWaypointCode() : String.valueOf(edge.neighborId()),
                from != null ? from.getFloorId() : null,
                edge.isCrossFloor(),
                edge.connType(),
                edge.cost());
    }

    private static boolean pointInZone(double px, double py, MapZone zone, Long floorId) {
        if (!zone.getFloorId().equals(floorId)) {
            return false;
        }
        try {
            var vertices = com.agv.navdeployer.metadata.MetadataJson.MAPPER.readValue(
                    zone.getVertices(), double[][].class);
            if (vertices.length < 3) {
                return false;
            }
            boolean inside = false;
            for (int i = 0, j = vertices.length - 1; i < vertices.length; j = i++) {
                if ((vertices[i][1] > py) != (vertices[j][1] > py)
                        && px < (vertices[j][0] - vertices[i][0]) * (py - vertices[i][1])
                                / (vertices[j][1] - vertices[i][1]) + vertices[i][0]) {
                    inside = !inside;
                }
            }
            return inside;
        } catch (Exception e) {
            return false;
        }
    }

    private Long findWaypointIdByCode(Map<Long, MapWaypoint> waypoints, String code) {
        return waypoints.values().stream()
                .filter(w -> code.equals(w.getWaypointCode()))
                .map(MapWaypoint::getId)
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<com.agv.navdeployer.metadata.vo.ConnEndpointVO> parseEndpoints(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return com.agv.navdeployer.metadata.MetadataJson.MAPPER.readValue(json, List.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<Long, MapWaypoint> loadWaypoints(Long mapId) {
        return waypointMapper.selectList(Wrappers.lambdaQuery(MapWaypoint.class)
                        .eq(MapWaypoint::getMapId, mapId))
                .stream()
                .collect(java.util.stream.Collectors.toMap(MapWaypoint::getId, w -> w));
    }

    private Map<Long, MapPath> loadDeployedPaths(Long mapId) {
        return pathMapper.selectList(Wrappers.lambdaQuery(MapPath.class)
                        .eq(MapPath::getMapId, mapId)
                        .eq(MapPath::getStatus, MapPath.STATUS_DEPLOYED))
                .stream()
                .collect(java.util.stream.Collectors.toMap(MapPath::getId, p -> p));
    }

    private Map<Long, List<MapPathSegment>> loadSegments(Set<Long> pathIds) {
        if (pathIds.isEmpty()) {
            return Map.of();
        }
        return segmentMapper.selectList(Wrappers.lambdaQuery(MapPathSegment.class)
                        .in(MapPathSegment::getPathId, pathIds)
                        .orderByAsc(MapPathSegment::getSeq))
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(MapPathSegment::getPathId));
    }
}
