package com.agv.navdeployer.metadata.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 区域约束（zoneConstraints 元素）：forbidden / slowDown / ramp。 */
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

    /** 坡道信息（type=ramp 时存在）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RampInfo(Double slope, String direction, Double startElevation, Double endElevation) {
    }
}
