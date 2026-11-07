package com.agv.navdeployer.map.application.dto;

import com.agv.navdeployer.map.domain.MapPath;

import java.util.ArrayList;
import java.util.List;

public record PathV2DTO(Long id,
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
                       List<SegmentDTO> segments) {

    public static PathV2DTO from(MapPath path, List<SegmentDTO> segments) {
        List<Long> chain = new ArrayList<>();
        List<SegmentDTO> safeSegments = segments == null ? List.of() : segments;
        for (int i = 0; i < safeSegments.size(); i++) {
            if (i == 0) {
                chain.add(safeSegments.get(i).sourceWaypointId());
            }
            chain.add(safeSegments.get(i).targetWaypointId());
        }
        return new PathV2DTO(path.getId(), path.getMapId(), path.getFloorId(), path.getPathCode(),
                path.getName(), path.getFromWaypointId(), path.getToWaypointId(),
                List.copyOf(chain), path.getIsBidirectional(), path.getMaxSpeed(),
                path.getEnableObstacleAvoidance(), path.getStatus(), safeSegments);
    }
}
