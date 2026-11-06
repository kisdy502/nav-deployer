package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.NotNull;

public record ShelfEdgeDTO(
        @NotNull Integer edgeIndex,
        @NotNull Integer vertexStart,
        @NotNull Integer vertexEnd,
        String direction) {
}
