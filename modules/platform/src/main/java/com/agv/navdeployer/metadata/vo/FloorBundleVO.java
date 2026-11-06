package com.agv.navdeployer.metadata.vo;

import java.util.List;

public record FloorBundleVO(FloorVO floor,
                            List<WaypointVO> waypoints,
                            List<ZoneVO> zones,
                            List<ShelfVO> shelves,
                            List<PathV2VO> paths) {
}
