package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ConnCreateCmd(
        @NotNull Long mapId,
        @NotBlank @Size(max = 64) String connCode,
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 16) String connType,
        @NotEmpty List<ConnEndpointCmd> connections,
        Double capacity,
        Double maxSpeed,
        ConnRampInfoCmd rampInfo,
        @Size(max = 64) String modifiedBy) {
}
