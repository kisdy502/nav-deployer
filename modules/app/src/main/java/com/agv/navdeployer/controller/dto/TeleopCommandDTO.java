package com.agv.navdeployer.controller.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record TeleopCommandDTO(
        @NotNull @DecimalMin("-1.5") @DecimalMax("1.5") Double linearX,
        @NotNull @DecimalMin("-1.5") @DecimalMax("1.5") Double linearY,
        @NotNull @DecimalMin("-2.5") @DecimalMax("2.5") Double angularZ
) {
}
