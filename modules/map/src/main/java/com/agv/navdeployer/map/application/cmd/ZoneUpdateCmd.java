package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.Size;

import java.util.List;

public record ZoneUpdateCmd(
        @Size(max = 128) String name,
        @Size(max = 16) String zoneType,
        Integer priority,
        Double maxSpeed,
        List<double[]> vertices,
        Double height,
        RampInfoCmd rampInfo,
        @Size(max = 512) String description,
        @Size(max = 64) String modifiedBy) {
}
