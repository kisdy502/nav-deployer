package com.agv.navdeployer.metadata.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LayerData(int layerIndex,
                        double elevation,
                        int accessEdge,
                        int numSlots,
                        List<SlotData> slots) {
}
