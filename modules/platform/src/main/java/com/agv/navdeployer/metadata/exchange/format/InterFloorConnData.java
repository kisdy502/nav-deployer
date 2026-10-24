package com.agv.navdeployer.metadata.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 楼层间连接（interFloorConnections 元素）：elevator / stairs / ramp。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InterFloorConnData(String id,
                                 String type,
                                 String name,
                                 List<Endpoint> connections,
                                 Double capacity,
                                 Double maxSpeed,
                                 RampMeta rampInfo) {

    /** 连接端点：楼层编码 + 点位编码。 */
    public record Endpoint(String floorId, String waypointId) {
    }

    /** 坡道元信息（type=ramp 时存在）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RampMeta(Double slope, Double length, String surface) {
    }
}
