package com.agv.navdeployer.rms.infrastructure.protocol.dto.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public final class MapReplyData {

    private MapReplyData() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkModeData(@JsonProperty("work_mode") int workMode) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MapNamesData(@JsonProperty("map_name") List<String> mapNames) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MapNameData(@JsonProperty("map_name") String mapName) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModeData(String mode) {
    }
}
