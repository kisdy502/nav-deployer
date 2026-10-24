package com.agv.navdeployer.metadata.exchange;

import com.agv.navdeployer.exchange.ScheduleMapAdapter;
import com.agv.navdeployer.metadata.vo.MapFullVO;
import com.agv.navdeployer.vo.MapGridVO;
import com.agv.navdeployer.vo.NavPathVO;
import com.agv.navdeployer.vo.NavPointVO;
import com.agv.navdeployer.vo.PathEdgeVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** V2 数据到旧调度拓扑的兼容投影，原始类型和曲线等完整信息保留在 map_info.json。 */
final class LegacyTopologyExporter {
    private LegacyTopologyExporter() {
    }

    static byte[] export(MapGridVO grid, MapFullVO full) {
        List<NavPointVO> points = new ArrayList<>();
        List<NavPathVO> paths = new ArrayList<>();
        Map<Long, List<PathEdgeVO>> edgesByPath = new LinkedHashMap<>();
        for (var floor : full.floors()) {
            for (var waypoint : floor.waypoints()) {
                NavPointVO point = new NavPointVO();
                point.setId(waypoint.id());
                point.setPointCode(waypoint.waypointCode());
                point.setX(waypoint.x());
                point.setY(waypoint.y());
                point.setYaw(waypoint.yaw());
                point.setPointType(legacyType(waypoint.wpType()));
                points.add(point);
            }
            for (var path : floor.paths()) {
                NavPathVO legacyPath = new NavPathVO();
                legacyPath.setId(path.id());
                paths.add(legacyPath);
                List<PathEdgeVO> edges = new ArrayList<>();
                for (var segment : path.segments()) {
                    double speed = segment.maxSpeed() != null ? segment.maxSpeed()
                            : path.maxSpeed() != null ? path.maxSpeed()
                            : floor.floor().defaultMaxSpeed() != null ? floor.floor().defaultMaxSpeed() : 0.6;
                    edges.add(edge(segment.sourceWaypointId(), segment.targetWaypointId(), speed, segment.reverse()));
                    if (Boolean.TRUE.equals(path.isBidirectional())) {
                        edges.add(edge(segment.targetWaypointId(), segment.sourceWaypointId(), speed, segment.reverse()));
                    }
                }
                edgesByPath.put(path.id(), edges);
            }
        }
        return new ScheduleMapAdapter().buildLxmap(grid, points, paths, edgesByPath, false);
    }

    static String legacyType(String type) {
        return switch (type == null ? "" : type.trim().toLowerCase(Locale.ROOT)) {
            case "work", "workstation" -> "WORK";
            case "charger", "charge" -> "CHARGER";
            case "home", "parking" -> "HOME";
            default -> "NORMAL";
        };
    }

    private static PathEdgeVO edge(Long source, Long target, double speed, Boolean reverse) {
        PathEdgeVO edge = new PathEdgeVO();
        edge.setSourcePointId(source);
        edge.setTargetPointId(target);
        edge.setMaxSpeed(speed);
        edge.setReverse(reverse);
        return edge;
    }
}
