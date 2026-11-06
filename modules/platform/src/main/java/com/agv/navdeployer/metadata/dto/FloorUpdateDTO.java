package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

public record FloorUpdateDTO(
        @Size(max = 128) String name,
        Double elevation,
        Double defaultMaxSpeed,
        Integer sortOrder,
        @Size(max = 64) String modifiedBy) {
}
