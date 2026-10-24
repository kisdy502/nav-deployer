package com.agv.navdeployer.metadata.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 货架占地多边形 + 边定义。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FootprintData(String shape, List<double[]> vertices, double height, List<Edge> edges) {

    /** 边定义：edgeIndex + 起止顶点索引（direction 为部分样例的可选标注）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Edge(int edgeIndex, int vertexStart, int vertexEnd, String direction) {
    }
}
