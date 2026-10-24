package com.agv.navdeployer.metadata.vo;

import java.util.List;

/** 单楼层全量数据包（编辑器按楼层加载）。 */
public record FloorBundleVO(FloorVO floor,
                            List<WaypointVO> waypoints,
                            List<ZoneVO> zones,
                            List<ShelfVO> shelves,
                            List<PathV2VO> paths) {
}
