package com.agv.navdeployer.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/** 路线的一段（边）。seq 由服务端按数组顺序生成，无需上送。 */
public record PathEdgeDTO(
        @NotNull(message = "source_point_id 不能为空") Long sourcePointId,
        @NotNull(message = "target_point_id 不能为空") Long targetPointId,
        @Pattern(regexp = "STRAIGHT|CURVE", message = "edge_type 只能是 STRAIGHT/CURVE") String edgeType,
        List<ControlPointDTO> controlPoints,
        @DecimalMin(value = "0.05", message = "max_speed 最小 0.05 m/s")
        @DecimalMax(value = "2.0", message = "max_speed 最大 2.0 m/s") Double maxSpeed,
        Boolean backUp,
        Boolean reverse
) {
}
