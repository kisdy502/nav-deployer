package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 新建楼层间连接（type：elevator/stairs/ramp；端点至少 2 个）。 */
public record ConnCreateDTO(
        @NotNull Long mapId,
        @NotBlank @Size(max = 64) String connCode,
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 16) String connType,
        @NotEmpty List<ConnEndpointDTO> connections,
        Double capacity,
        Double maxSpeed,
        ConnRampInfoDTO rampInfo,
        @Size(max = 64) String modifiedBy) {
}
