package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WaypointCreateCmd(
        @NotNull Long mapId,
        @NotNull Long floorId,
        @NotBlank @Size(max = 64) String waypointCode,
        @Size(max = 128) String name,
        @Size(max = 32) String wpType,
        @NotNull Double x,
        @NotNull Double y,
        Double yaw,
        @Size(max = 256) String remark,
        @Size(max = 64) String modifiedBy) {
}
