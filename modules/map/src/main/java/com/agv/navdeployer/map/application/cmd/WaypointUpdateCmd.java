package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.Size;

public record WaypointUpdateCmd(
        @Size(max = 64) String name,
        @Size(max = 32) String wpType,
        Double x,
        Double y,
        Double yaw,
        @Size(max = 256) String remark,
        @Size(max = 64) String modifiedBy) {
}
