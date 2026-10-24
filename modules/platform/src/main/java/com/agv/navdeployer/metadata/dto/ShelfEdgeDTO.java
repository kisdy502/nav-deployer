package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.NotNull;

/** 货架边定义（edgeIndex + 起止顶点索引；缺省按顶点顺序自动生成闭环边）。 */
public record ShelfEdgeDTO(
        @NotNull Integer edgeIndex,
        @NotNull Integer vertexStart,
        @NotNull Integer vertexEnd,
        String direction) {
}
