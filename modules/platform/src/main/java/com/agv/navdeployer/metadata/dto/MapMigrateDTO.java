package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

public record MapMigrateDTO(
        @Size(max = 64) String mapName,
        @Size(max = 64) String modifiedBy) {
}
