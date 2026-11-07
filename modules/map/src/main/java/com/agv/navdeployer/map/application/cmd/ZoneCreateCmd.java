package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ZoneCreateCmd(
        @NotNull Long mapId,
        @NotNull Long floorId,
        @NotBlank @Size(max = 64) String zoneCode,
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 16) String zoneType,
        Integer priority,
        Double maxSpeed,
        @NotEmpty List<double[]> vertices,
        Double height,
        RampInfoCmd rampInfo,
        @Size(max = 512) String description,
        @Size(max = 64) String modifiedBy) {
}
