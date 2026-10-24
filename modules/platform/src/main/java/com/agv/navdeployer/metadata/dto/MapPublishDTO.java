package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

/** 发布地图（版本 +1，全局唯一 PUBLISHED）。 */
public record MapPublishDTO(@Size(max = 64) String modifiedBy) {
}
