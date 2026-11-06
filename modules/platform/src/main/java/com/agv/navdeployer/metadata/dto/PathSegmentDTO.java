package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.NotNull;

public record PathSegmentDTO(
        @NotNull Long sourceWaypointId,
        @NotNull Long targetWaypointId,
        String segmentType,
        java.util.List<double[]> controlPoints,
        Double maxSpeed,
        Boolean backUp,
        Boolean reverse) {
}
