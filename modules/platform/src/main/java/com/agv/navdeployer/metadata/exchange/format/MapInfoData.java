package com.agv.navdeployer.metadata.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MapInfoData(String mapName,
                          String coordinateSystem,
                          String unit,
                          List<FloorData> floors,
                          List<InterFloorConnData> interFloorConnections) {
}
