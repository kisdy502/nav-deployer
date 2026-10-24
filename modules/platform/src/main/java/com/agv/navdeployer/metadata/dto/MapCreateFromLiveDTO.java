package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 从实时栅格建图（v2 多楼层格式，自动创建默认楼层 F1）。 */
public record MapCreateFromLiveDTO(
        @NotBlank @Size(max = 64) String mapName,
        @Size(max = 64) String modifiedBy) {
}
