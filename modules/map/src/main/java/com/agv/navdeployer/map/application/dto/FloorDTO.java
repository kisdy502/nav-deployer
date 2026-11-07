package com.agv.navdeployer.map.application.dto;

import com.agv.navdeployer.map.domain.MapFloor;

public record FloorDTO(Long id,
                      Long mapId,
                      String floorCode,
                      String name,
                      Double elevation,
                      Double defaultMaxSpeed,
                      Integer sortOrder) {

    public static FloorDTO from(MapFloor floor) {
        return new FloorDTO(floor.getId(), floor.getMapId(), floor.getFloorCode(), floor.getName(),
                floor.getElevation(), floor.getDefaultMaxSpeed(), floor.getSortOrder());
    }
}
