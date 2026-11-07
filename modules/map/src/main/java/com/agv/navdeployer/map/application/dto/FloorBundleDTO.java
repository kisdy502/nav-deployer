package com.agv.navdeployer.map.application.dto;

import java.util.List;

public record FloorBundleDTO(FloorDTO floor,
                            List<WaypointDTO> waypoints,
                            List<ZoneDTO> zones,
                            List<ShelfDTO> shelves,
                            List<PathV2DTO> paths) {
}
