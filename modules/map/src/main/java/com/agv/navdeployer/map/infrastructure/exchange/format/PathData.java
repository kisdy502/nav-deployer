package com.agv.navdeployer.map.infrastructure.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PathData(String id,
                       String from,
                       String to,
                       List<String> waypoints,
                       boolean isBidirectional,
                       Double maxSpeed,
                       Boolean enableObstacleAvoidance,
                       List<Segment> segments) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Segment(int seq,
                          String from,
                          String to,
                          String segmentType,
                          List<double[]> controlPoints,
                          Double maxSpeed,
                          Boolean backUp,
                          Boolean reverse) {
    }
}
