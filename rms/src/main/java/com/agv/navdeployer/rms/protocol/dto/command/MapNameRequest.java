package com.agv.navdeployer.rms.protocol.dto.command;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RMS 地图类指令的 payload（start_mapping / stop_mapping / mapping/change / mapping/delete）。
 * 线上字段为 {@code map_name}；缺省/空白统一归一为 null，由 RmsMapService 决定取值优先级。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MapNameRequest(@JsonProperty("map_name") String mapName) {

    @JsonCreator
    public MapNameRequest {
        if (mapName != null && mapName.isBlank()) {
            mapName = null;
        }
    }
}
