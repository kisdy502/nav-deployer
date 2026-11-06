package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ShelfSlotDTO(
        @NotNull Integer slotIndex,
        @NotNull double[] position,
        double[] size,
        String productId,
        String productName) {
}
