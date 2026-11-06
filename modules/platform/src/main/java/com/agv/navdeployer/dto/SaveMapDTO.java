package com.agv.navdeployer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SaveMapDTO(
        @NotBlank(message = "地图名不能为空") @Size(max = 64, message = "地图名最多 64 字符") String mapName
) {
}
