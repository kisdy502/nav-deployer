package com.agv.navdeployer.metadata.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SlotData(int slotIndex, double[] position, double[] size, Product product) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Product(String id, String name) {
    }
}
