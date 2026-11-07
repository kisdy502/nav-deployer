package com.agv.navdeployer.map.application.dto;

import java.util.List;

public record ShelfLayerDTO(Long id,
                           Integer layerIndex,
                           Double elevation,
                           Integer accessEdge,
                           Integer numSlots,
                           List<ShelfSlotDTO> slots) {
}
