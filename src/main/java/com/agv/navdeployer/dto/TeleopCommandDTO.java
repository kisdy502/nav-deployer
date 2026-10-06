package com.agv.navdeployer.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** 车体坐标系速度指令：x 前进、y 向左横移、z 逆时针旋转。 */
public record TeleopCommandDTO(
        @NotNull @DecimalMin("-1.5") @DecimalMax("1.5") Double linearX,
        @NotNull @DecimalMin("-1.5") @DecimalMax("1.5") Double linearY,
        @NotNull @DecimalMin("-2.5") @DecimalMax("2.5") Double angularZ
) {
}
