package com.agv.navdeployer.metadata.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ZoneData(String id,
                       String name,
                       String type,
                       int priority,
                       double maxSpeed,
                       String shape,
                       Double height,
                       List<double[]> vertices,
                       RampInfo rampInfo,
                       String description) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RampInfo(Double slope, String direction, Double startElevation, Double endElevation) {
    }
}
