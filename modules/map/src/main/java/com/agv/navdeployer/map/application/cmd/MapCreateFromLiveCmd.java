package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MapCreateFromLiveCmd(
        @NotBlank @Size(max = 64) String mapName,
        @Size(max = 64) String modifiedBy) {
}
