package com.agv.navdeployer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 手动创建点位（map 系坐标，yaw 弧度）。 */
public record NavPointCreateDTO(
        @NotNull(message = "map_id 不能为空") Long mapId,
        @NotBlank(message = "点位编码不能为空") @Size(max = 64) String pointCode,
        @Pattern(regexp = "NORMAL|CHARGER|HOME", message = "point_type 只能是 NORMAL/CHARGER/HOME") String pointType,
        @NotNull(message = "x 不能为空") Double x,
        @NotNull(message = "y 不能为空") Double y,
        @NotNull(message = "yaw 不能为空") Double yaw,
        @Size(max = 256) String remark
) {
}
