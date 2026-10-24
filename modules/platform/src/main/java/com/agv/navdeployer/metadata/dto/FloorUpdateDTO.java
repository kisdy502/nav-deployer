package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

/** 更新楼层（字段全部可选，floorCode 不可改）。 */
public record FloorUpdateDTO(
        @Size(max = 128) String name,
        Double elevation,
        Double defaultMaxSpeed,
        Integer sortOrder,
        @Size(max = 64) String modifiedBy) {
}
