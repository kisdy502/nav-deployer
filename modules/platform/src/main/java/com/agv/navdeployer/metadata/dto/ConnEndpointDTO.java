package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.NotBlank;

/** 楼层间连接端点：楼层编码 + 点位编码。 */
public record ConnEndpointDTO(
        @NotBlank String floorId,
        @NotBlank String waypointId) {
}
