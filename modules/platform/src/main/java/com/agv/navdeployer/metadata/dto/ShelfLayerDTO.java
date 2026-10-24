package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 货架层：layerIndex 从 0 开始（底层 0），accessEdge 指向 footprint 边索引。 */
public record ShelfLayerDTO(
        @NotNull Integer layerIndex,
        @NotNull Double elevation,
        Integer accessEdge,
        List<ShelfSlotDTO> slots) {
}
