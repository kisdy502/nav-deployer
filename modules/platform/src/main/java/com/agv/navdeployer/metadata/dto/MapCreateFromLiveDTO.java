package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MapCreateFromLiveDTO(
        @NotBlank @Size(max = 64) String mapName,
        @Size(max = 64) String modifiedBy) {
}
