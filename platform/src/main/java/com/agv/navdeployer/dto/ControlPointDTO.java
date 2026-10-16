package com.agv.navdeployer.dto;

import jakarta.validation.constraints.NotNull;

/** 贝塞尔控制点（曲线边 1~2 个）。 */
public record ControlPointDTO(
        @NotNull(message = "控制点 x 不能为空") Double x,
        @NotNull(message = "控制点 y 不能为空") Double y
) {
}
