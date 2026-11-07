package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ShelfSlotCmd(
        @NotNull Integer slotIndex,
        @NotNull double[] position,
        double[] size,
        String productId,
        String productName) {
}
