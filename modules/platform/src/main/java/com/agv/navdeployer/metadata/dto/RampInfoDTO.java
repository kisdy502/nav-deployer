package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.NotNull;

/** 坡道信息（zone_type=ramp 时必填）。 */
public record RampInfoDTO(
        @NotNull Double slope,
        @NotNull String direction,
        @NotNull Double startElevation,
        @NotNull Double endElevation) {
}
