package com.agv.navdeployer.map.infrastructure.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InterFloorConnData(String id,
                                 String type,
                                 String name,
                                 List<Endpoint> connections,
                                 Double capacity,
                                 Double maxSpeed,
                                 RampMeta rampInfo) {

    public record Endpoint(String floorId, String waypointId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RampMeta(Double slope, Double length, String surface) {
    }
}
