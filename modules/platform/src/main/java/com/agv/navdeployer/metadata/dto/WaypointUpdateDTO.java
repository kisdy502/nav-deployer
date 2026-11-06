package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

public record WaypointUpdateDTO(
        @Size(max = 64) String name,
        @Size(max = 32) String wpType,
        Double x,
        Double y,
        Double yaw,
        @Size(max = 256) String remark,
        @Size(max = 64) String modifiedBy) {
}
