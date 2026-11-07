package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FloorCreateCmd(
        @NotNull Long mapId,
        @NotBlank @Size(max = 32) String floorCode,
        @NotBlank @Size(max = 128) String name,
        @NotNull Double elevation,
        @NotNull Double defaultMaxSpeed,
        Integer sortOrder,
        @Size(max = 64) String modifiedBy) {
}
