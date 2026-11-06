package com.agv.navdeployer.dto;

import jakarta.validation.constraints.NotNull;

public record ControlPointDTO(
        @NotNull(message = "控制点 x 不能为空") Double x,
        @NotNull(message = "控制点 y 不能为空") Double y
) {
}
