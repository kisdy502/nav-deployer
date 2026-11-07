package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.Size;

public record FloorUpdateCmd(
        @Size(max = 128) String name,
        Double elevation,
        Double defaultMaxSpeed,
        Integer sortOrder,
        @Size(max = 64) String modifiedBy) {
}
