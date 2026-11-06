package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.NotNull;

public record RampInfoDTO(
        @NotNull Double slope,
        @NotNull String direction,
        @NotNull Double startElevation,
        @NotNull Double endElevation) {
}
