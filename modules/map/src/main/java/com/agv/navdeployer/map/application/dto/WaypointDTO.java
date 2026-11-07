package com.agv.navdeployer.map.application.dto;

import com.agv.navdeployer.map.domain.MapWaypoint;

public record WaypointDTO(Long id,
                         Long mapId,
                         Long floorId,
                         String waypointCode,
                         String name,
                         String wpType,
                         Double x,
                         Double y,
                         Double yaw,
                         String remark) {

    public static WaypointDTO from(MapWaypoint waypoint) {
        return new WaypointDTO(waypoint.getId(), waypoint.getMapId(), waypoint.getFloorId(),
                waypoint.getWaypointCode(), waypoint.getName(), waypoint.getWpType(),
                waypoint.getX(), waypoint.getY(), waypoint.getYaw(), waypoint.getRemark());
    }
}
