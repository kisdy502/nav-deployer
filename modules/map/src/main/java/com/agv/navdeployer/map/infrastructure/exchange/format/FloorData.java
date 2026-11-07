package com.agv.navdeployer.map.infrastructure.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

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
