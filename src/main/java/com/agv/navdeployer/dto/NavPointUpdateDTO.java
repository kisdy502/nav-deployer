package com.agv.navdeployer.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 更新点位（坐标 / 类型 / 备注），未传字段保持不变。 */
public record NavPointUpdateDTO(
        @Size(max = 64) String pointCode,
        @Pattern(regexp = "NORMAL|CHARGER|HOME", message = "point_type 只能是 NORMAL/CHARGER/HOME") String pointType,
        Double x,
        Double y,
        Double yaw,
        @Size(max = 256) String remark
) {
}
