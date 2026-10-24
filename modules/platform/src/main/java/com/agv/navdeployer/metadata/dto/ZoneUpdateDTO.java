package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/** 更新区域（字段全部可选；vertices 非空时整体替换）。 */
public record ZoneUpdateDTO(
        @Size(max = 128) String name,
        @Size(max = 16) String zoneType,
        Integer priority,
        Double maxSpeed,
        List<double[]> vertices,
        Double height,
        RampInfoDTO rampInfo,
        @Size(max = 512) String description,
        @Size(max = 64) String modifiedBy) {
}
