package com.agv.navdeployer.metadata.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 点位（waypoints 元素）：position = [x, y, theta(弧度)]。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WaypointData(String id, String name, double[] position, String type) {
}
