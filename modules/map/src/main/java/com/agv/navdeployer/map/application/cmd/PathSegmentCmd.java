package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.NotNull;

public record PathSegmentCmd(
        @NotNull Long sourceWaypointId,
        @NotNull Long targetWaypointId,
        String segmentType,
        java.util.List<double[]> controlPoints,
        Double maxSpeed,
        Boolean backUp,
        Boolean reverse) {
}
