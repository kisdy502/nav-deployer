package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 货架格位：position=[x,y,z]（米），size=[width,depth,height] 缺省 [0.4,0.6,0.3]。 */
public record ShelfSlotDTO(
        @NotNull Integer slotIndex,
        @NotNull double[] position,
        double[] size,
        String productId,
        String productName) {
}
