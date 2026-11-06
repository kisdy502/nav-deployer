package com.agv.navdeployer.dto;

import jakarta.validation.constraints.NotNull;

public record InitialPoseDTO(
        @NotNull(message = "x 不能为空") Double x,
        @NotNull(message = "y 不能为空") Double y,
        @NotNull(message = "theta 不能为空") Double theta,
        String mapName
) {
}
