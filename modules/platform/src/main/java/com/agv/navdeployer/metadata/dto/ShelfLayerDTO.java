package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ShelfLayerDTO(
        @NotNull Integer layerIndex,
        @NotNull Double elevation,
        Integer accessEdge,
        List<ShelfSlotDTO> slots) {
}
