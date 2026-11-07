package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.Size;

import java.util.List;

public record PathSaveCmd(
        Long mapId,
        Long floorId,
        @Size(max = 64) String pathCode,
        @Size(max = 128) String name,
        List<Long> waypointIds,
        Boolean isBidirectional,
        Double maxSpeed,
        Boolean enableObstacleAvoidance,
        List<PathSegmentCmd> segments,
        @Size(max = 16) String status,
        @Size(max = 64) String modifiedBy) {
}
