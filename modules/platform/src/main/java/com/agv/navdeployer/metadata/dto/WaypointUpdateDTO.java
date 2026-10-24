package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

/** 更新点位（字段全部可选，waypointCode 不可改）。 */
public record WaypointUpdateDTO(
        @Size(max = 128) String name,
        @Size(max = 32) String wpType,
        Double x,
        Double y,
        Double yaw,
        @Size(max = 256) String remark,
        @Size(max = 64) String modifiedBy) {
}
