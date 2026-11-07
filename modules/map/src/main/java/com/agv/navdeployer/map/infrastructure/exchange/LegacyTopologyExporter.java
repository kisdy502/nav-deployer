package com.agv.navdeployer.map.infrastructure.exchange;

import com.agv.navdeployer.map.infrastructure.exchange.ScheduleMapAdapter;
import com.agv.navdeployer.map.application.dto.MapFullDTO;
import com.agv.navdeployer.map.application.dto.MapGridDTO;
import com.agv.navdeployer.map.infrastructure.exchange.NavPathVO;
import com.agv.navdeployer.map.infrastructure.exchange.NavPointVO;
import com.agv.navdeployer.map.infrastructure.exchange.PathEdgeVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LegacyTopologyExporter {
    private LegacyTopologyExporter() {
    }

    public static byte[] export(MapGridDTO grid, MapFullDTO full) {
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
