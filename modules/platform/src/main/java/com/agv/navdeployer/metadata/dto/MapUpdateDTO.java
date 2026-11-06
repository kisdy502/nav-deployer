package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

public record MapUpdateDTO(
        @Size(max = 64) String mapName,
        @Size(max = 128) String robotMapName,
        @Size(max = 64) String modifiedBy) {
}
