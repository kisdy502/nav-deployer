package com.agv.navdeployer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 把机器人当前位姿（/tf map 系）标记为点位。 */
public record NavPointFromPoseDTO(
        @NotNull(message = "map_id 不能为空") Long mapId,
        @NotBlank(message = "点位编码不能为空") @Size(max = 64) String pointCode,
        @Pattern(regexp = "NORMAL|CHARGER|HOME", message = "point_type 只能是 NORMAL/CHARGER/HOME") String pointType,
        @Size(max = 256) String remark
) {
}
