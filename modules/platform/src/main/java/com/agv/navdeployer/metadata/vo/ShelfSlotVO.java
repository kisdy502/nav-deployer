package com.agv.navdeployer.metadata.vo;

public record ShelfSlotVO(Long id,
                          Integer slotIndex,
                          double[] position,
                          double[] size,
                          String productId,
                          String productName) {
}
