package com.agv.navdeployer.metadata.vo;

/** 货架格位视图：position=[x,y,z]，size=[width,depth,height]。 */
public record ShelfSlotVO(Long id,
                          Integer slotIndex,
                          double[] position,
                          double[] size,
                          String productId,
                          String productName) {
}
