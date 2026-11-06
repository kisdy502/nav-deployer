package com.agv.navdeployer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RobotControlDTO(
        @NotBlank @Pattern(regexp = "start|stop|reset", message = "action 只能是 start/stop/reset") String action
) {
}
