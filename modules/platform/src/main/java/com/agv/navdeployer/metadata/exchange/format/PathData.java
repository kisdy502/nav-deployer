package com.agv.navdeployer.metadata.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 路径（paths 元素）。
 *
 * <p>segments 为本系统扩展字段（新格式暂无曲线）：仅当存在曲线段或段级差异时导出，
 * 携带旧格式的贝塞尔控制点等曲线能力；不识别该字段的消费方按标准格式忽略。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PathData(String id,
                       String from,
                       String to,
                       List<String> waypoints,
                       boolean isBidirectional,
                       Double maxSpeed,
                       Boolean enableObstacleAvoidance,
                       List<Segment> segments) {

    /** 扩展段：from/to 为点位编码，controlPoints 为 1~2 个贝塞尔控制点 [x,y]。 */
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
