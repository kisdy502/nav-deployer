package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ShelfLayerCmd(
        @NotNull Integer layerIndex,
        @NotNull Double elevation,
        Integer accessEdge,
        List<ShelfSlotCmd> slots) {
}
