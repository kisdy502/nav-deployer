package com.agv.navdeployer.metadata.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 货架格位（slots 元素）：position = [x,y,z]、size = [width,depth,height]，product 为 null 表示空位。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SlotData(int slotIndex, double[] position, double[] size, Product product) {

    /** 商品信息（库存明细由独立库存系统维护，此处仅位置与标识）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Product(String id, String name) {
    }
}
