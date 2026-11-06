package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

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
