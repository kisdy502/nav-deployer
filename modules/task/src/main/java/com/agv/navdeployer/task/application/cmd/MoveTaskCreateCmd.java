package com.agv.navdeployer.task.application.cmd;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record MoveTaskCreateCmd(
        @NotNull(message = "task_type 不能为空")
        @Pattern(regexp = "TO_WAYPOINT|GOAL|FOLLOW_PATH|FREE", message = "task_type 只能是 TO_WAYPOINT/GOAL/FOLLOW_PATH/FREE")
        String taskType,
        Long waypointId,
        Long pathId,
        Double x,
        Double y,
        Double theta,
        @DecimalMin(value = "0.05", message = "max_speed 最小 0.05 m/s")
        @DecimalMax(value = "2.0", message = "max_speed 最大 2.0 m/s") Double maxSpeed,
        Boolean endPoint,
        List<ChainLeg> chain
) {

    public MoveTaskCreateCmd(String taskType, Long waypointId, Long pathId,
                             Double x, Double y, Double theta, Double maxSpeed, Boolean endPoint) {
        this(taskType, waypointId, pathId, x, y, theta, maxSpeed, endPoint, null);
    }

    /**
     * 跨路线执行链中的一条路线：pathId + 该路线的进出口点位编码
     * （fromCode/toCode 为 null 表示未定向，沿用单路线最近端进入/整线方向）。
     */
    public record ChainLeg(Long pathId, String fromCode, String toCode) {
    }
}
