package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.Size;

import java.util.List;

public record ConnUpdateCmd(
        @Size(max = 128) String name,
        @Size(max = 16) String connType,
        List<ConnEndpointCmd> connections,
        Double capacity,
        Double maxSpeed,
        ConnRampInfoCmd rampInfo,
        @Size(max = 64) String modifiedBy) {
}
