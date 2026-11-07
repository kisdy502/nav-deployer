package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.NotNull;

public record RampInfoCmd(
        @NotNull Double slope,
        @NotNull String direction,
        @NotNull Double startElevation,
        @NotNull Double endElevation) {
}
