package com.agv.navdeployer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 从实时栅格快照建图。 */
public record NavMapCreateDTO(
        @NotBlank(message = "地图名称不能为空") @Size(max = 64, message = "地图名称最长 64 字符") String mapName
) {
}
