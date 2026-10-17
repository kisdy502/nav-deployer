package com.agv.navdeployer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 机器人任务闸门：start 恢复接单 / stop 停车暂停 / reset 复位。 */
public record RobotControlDTO(
        @NotBlank @Pattern(regexp = "start|stop|reset", message = "action 只能是 start/stop/reset") String action
) {
}
