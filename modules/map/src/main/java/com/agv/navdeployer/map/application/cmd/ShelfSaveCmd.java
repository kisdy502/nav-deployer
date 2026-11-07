package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.Size;

import java.util.List;

public record ShelfSaveCmd(
        Long mapId,
        Long floorId,
        @Size(max = 64) String shelfCode,
        @Size(max = 128) String name,
        List<double[]> vertices,
        Double height,
        List<ShelfEdgeCmd> edges,
        List<ShelfLayerCmd> layers,
        @Size(max = 64) String modifiedBy) {
}
