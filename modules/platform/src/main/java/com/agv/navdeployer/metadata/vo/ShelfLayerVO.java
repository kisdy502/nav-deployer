package com.agv.navdeployer.metadata.vo;

import java.util.List;

/** 货架层视图（含格位）。 */
public record ShelfLayerVO(Long id,
                           Integer layerIndex,
                           Double elevation,
                           Integer accessEdge,
                           Integer numSlots,
                           List<ShelfSlotVO> slots) {
}
