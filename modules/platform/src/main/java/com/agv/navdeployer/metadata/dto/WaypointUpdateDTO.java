package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

/** 更新点位：name 即编码/显示名（code = name，改 name 就是改 code，级联更新引用）。 */
public record WaypointUpdateDTO(
        @Size(max = 64) String name,
        @Size(max = 32) String wpType,
        Double x,
        Double y,
        Double yaw,
        @Size(max = 256) String remark,
        @Size(max = 64) String modifiedBy) {
}
