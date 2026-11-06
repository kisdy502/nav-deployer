package com.agv.navdeployer.metadata.vo;

import java.util.List;

public record ShelfLayerVO(Long id,
                           Integer layerIndex,
                           Double elevation,
                           Integer accessEdge,
                           Integer numSlots,
                           List<ShelfSlotVO> slots) {
}
