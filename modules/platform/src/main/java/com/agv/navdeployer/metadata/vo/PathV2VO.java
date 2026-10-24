package com.agv.navdeployer.metadata.vo;

import com.agv.navdeployer.metadata.entity.MapPath;

import java.util.ArrayList;
import java.util.List;

/** 路线视图（waypointIds 由段链推导：首段 source + 各段 target，保留重复途经）。 */
public record PathV2VO(Long id,
                       Long mapId,
                       Long floorId,
                       String pathCode,
                       String name,
                       Long fromWaypointId,
                       Long toWaypointId,
                       List<Long> waypointIds,
                       Boolean isBidirectional,
                       Double maxSpeed,
                       Boolean enableObstacleAvoidance,
                       String status,
                       List<SegmentVO> segments) {

    public static PathV2VO from(MapPath path, List<SegmentVO> segments) {
        List<Long> chain = new ArrayList<>();
        List<SegmentVO> safeSegments = segments == null ? List.of() : segments;
        for (int i = 0; i < safeSegments.size(); i++) {
            if (i == 0) {
                chain.add(safeSegments.get(i).sourceWaypointId());
            }
            chain.add(safeSegments.get(i).targetWaypointId());
        }
        return new PathV2VO(path.getId(), path.getMapId(), path.getFloorId(), path.getPathCode(),
                path.getName(), path.getFromWaypointId(), path.getToWaypointId(),
                List.copyOf(chain), path.getIsBidirectional(), path.getMaxSpeed(),
                path.getEnableObstacleAvoidance(), path.getStatus(), safeSegments);
    }
}
