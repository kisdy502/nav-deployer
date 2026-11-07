package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.NotBlank;

public record ConnEndpointCmd(
        @NotBlank String floorId,
        @NotBlank String waypointId) {
}
