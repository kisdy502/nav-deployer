package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.Size;

public record MapUpdateCmd(
        @Size(max = 64) String mapName,
        @Size(max = 128) String robotMapName,
        @Size(max = 64) String modifiedBy) {
}
