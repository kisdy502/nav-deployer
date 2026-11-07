package com.agv.navdeployer.map.application.dto;

public record ShelfSlotDTO(Long id,
                          Integer slotIndex,
                          double[] position,
                          double[] size,
                          String productId,
                          String productName) {
}
