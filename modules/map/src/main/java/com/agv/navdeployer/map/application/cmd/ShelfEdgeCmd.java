package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.NotNull;

public record ShelfEdgeCmd(
        @NotNull Integer edgeIndex,
        @NotNull Integer vertexStart,
        @NotNull Integer vertexEnd,
        String direction) {
}
