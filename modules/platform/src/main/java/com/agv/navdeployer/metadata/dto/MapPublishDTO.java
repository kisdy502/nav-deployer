package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

public record MapPublishDTO(@Size(max = 64) String modifiedBy) {
}
