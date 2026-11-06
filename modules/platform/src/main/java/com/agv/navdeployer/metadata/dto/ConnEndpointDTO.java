package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.NotBlank;

public record ConnEndpointDTO(
        @NotBlank String floorId,
        @NotBlank String waypointId) {
}
