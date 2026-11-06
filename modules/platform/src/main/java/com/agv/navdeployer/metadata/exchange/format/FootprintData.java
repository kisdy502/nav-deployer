package com.agv.navdeployer.metadata.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FootprintData(String shape, List<double[]> vertices, double height, List<Edge> edges) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Edge(int edgeIndex, int vertexStart, int vertexEnd, String direction) {
    }
}
