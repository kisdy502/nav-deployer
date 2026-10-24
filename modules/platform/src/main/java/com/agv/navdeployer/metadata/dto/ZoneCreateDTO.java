package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 新建区域（type：forbidden/slowDown/ramp；vertices 至少 3 个顶点 [x,y]）。 */
public record ZoneCreateDTO(
        @NotNull Long mapId,
        @NotNull Long floorId,
        @NotBlank @Size(max = 64) String zoneCode,
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 16) String zoneType,
        Integer priority,
        Double maxSpeed,
        @NotEmpty List<double[]> vertices,
        Double height,
        RampInfoDTO rampInfo,
        @Size(max = 512) String description,
        @Size(max = 64) String modifiedBy) {
}
