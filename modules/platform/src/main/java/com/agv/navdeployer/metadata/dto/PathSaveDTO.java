package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

public record PathSaveDTO(
        Long mapId,
        Long floorId,
        @Size(max = 64) String pathCode,
        @Size(max = 128) String name,
        List<Long> waypointIds,
        Boolean isBidirectional,
        Double maxSpeed,
        Boolean enableObstacleAvoidance,
        List<PathSegmentDTO> segments,
        @Size(max = 16) String status,
        @Size(max = 64) String modifiedBy) {
}
