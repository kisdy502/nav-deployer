package com.agv.navdeployer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 整体替换路线的边序列（要求首尾相接、点属于同一地图）。 */
public record NavPathEdgesUpdateDTO(
        @NotEmpty(message = "edges 不能为空") List<@Valid PathEdgeDTO> edges
) {
}
