package com.agv.navdeployer.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 创建移动任务：TO_POINT（按 point_id）/ GOAL（按坐标）/ FOLLOW_PATH（按 path_id 逐段执行）。
 * max_speed / end_point 可选，默认取配置。
 */
public record MoveTaskCreateDTO(
        @NotNull(message = "task_type 不能为空")
        @Pattern(regexp = "TO_POINT|GOAL|FOLLOW_PATH", message = "task_type 只能是 TO_POINT/GOAL/FOLLOW_PATH")
        String taskType,
        Long pointId,
        Long pathId,
        Double x,
        Double y,
        Double theta,
        @DecimalMin(value = "0.05", message = "max_speed 最小 0.05 m/s")
        @DecimalMax(value = "2.0", message = "max_speed 最大 2.0 m/s") Double maxSpeed,
        Boolean endPoint
) {
}
