package com.agv.navdeployer.metadata.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 楼层（floors 元素）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FloorData(String floorId,
                        String name,
                        double elevation,
                        double defaultMaxSpeed,
                        List<WaypointData> waypoints,
                        List<ZoneData> zoneConstraints,
                        List<ShelfData> shelves,
                        List<PathData> paths) {
}
