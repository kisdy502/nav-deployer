package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

public record ShelfSaveDTO(
        Long mapId,
        Long floorId,
        @Size(max = 64) String shelfCode,
        @Size(max = 128) String name,
        List<double[]> vertices,
        Double height,
        List<ShelfEdgeDTO> edges,
        List<ShelfLayerDTO> layers,
        @Size(max = 64) String modifiedBy) {
}
