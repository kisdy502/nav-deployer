package com.agv.navdeployer.metadata.vo;

import com.agv.navdeployer.metadata.entity.MapFloor;

/** 楼层视图。 */
public record FloorVO(Long id,
                      Long mapId,
                      String floorCode,
                      String name,
                      Double elevation,
                      Double defaultMaxSpeed,
                      Integer sortOrder) {

    public static FloorVO from(MapFloor floor) {
        return new FloorVO(floor.getId(), floor.getMapId(), floor.getFloorCode(), floor.getName(),
                floor.getElevation(), floor.getDefaultMaxSpeed(), floor.getSortOrder());
    }
}
