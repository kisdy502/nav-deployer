package com.agv.navdeployer.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record MoveTaskCreateDTO(
        @NotNull(message = "task_type 不能为空")
        @Pattern(regexp = "TO_POINT|TO_WAYPOINT|GOAL|FOLLOW_PATH|FREE", message = "task_type 只能是 TO_POINT/TO_WAYPOINT/GOAL/FOLLOW_PATH/FREE")
        String taskType,
        Long pointId,
        Long waypointId,
        Long pathId,
        Double x,
        Double y,
        Double theta,
        @DecimalMin(value = "0.05", message = "max_speed 最小 0.05 m/s")
        @DecimalMax(value = "2.0", message = "max_speed 最大 2.0 m/s") Double maxSpeed,
        Boolean endPoint
) {
}
