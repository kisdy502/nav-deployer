package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.NotNull;

/** 路线段：segmentType STRAIGHT/CURVE；CURVE 必须带 1~2 个贝塞尔控制点 [x,y]，STRAIGHT 不得带。 */
public record PathSegmentDTO(
        @NotNull Long sourceWaypointId,
        @NotNull Long targetWaypointId,
        String segmentType,
        java.util.List<double[]> controlPoints,
        Double maxSpeed,
        Boolean backUp,
        Boolean reverse) {
}
