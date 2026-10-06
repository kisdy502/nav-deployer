package com.agv.navdeployer.dto;

import jakarta.validation.constraints.NotNull;

/** 重定位（调机器人 /agv/relocalize 服务），map 系坐标。 */
public record InitialPoseDTO(
        @NotNull(message = "x 不能为空") Double x,
        @NotNull(message = "y 不能为空") Double y,
        @NotNull(message = "theta 不能为空") Double theta,
        /** 目标地图名；缺省取机器人当前地图（/agv/status.map_name） */
        String mapName
) {
}
