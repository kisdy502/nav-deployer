package com.agv.navdeployer.rms.infrastructure.protocol.dto.command;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MapNameRequest(@JsonProperty("map_name") String mapName) {

    @JsonCreator
    public MapNameRequest {
        if (mapName != null && mapName.isBlank()) {
            mapName = null;
        }
    }
}
