package com.agv.navdeployer.metadata.vo;

import com.agv.navdeployer.metadata.entity.MapWaypoint;

/** 点位视图。 */
public record WaypointVO(Long id,
                         Long mapId,
                         Long floorId,
                         String waypointCode,
                         String name,
                         String wpType,
                         Double x,
                         Double y,
                         Double yaw,
                         String remark) {

    public static WaypointVO from(MapWaypoint waypoint) {
        return new WaypointVO(waypoint.getId(), waypoint.getMapId(), waypoint.getFloorId(),
                waypoint.getWaypointCode(), waypoint.getName(), waypoint.getWpType(),
                waypoint.getX(), waypoint.getY(), waypoint.getYaw(), waypoint.getRemark());
    }
}
