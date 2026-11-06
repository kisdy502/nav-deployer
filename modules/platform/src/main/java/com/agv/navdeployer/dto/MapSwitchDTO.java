package com.agv.navdeployer.dto;

import jakarta.validation.constraints.Size;

public record MapSwitchDTO(
        @Size(max = 64, message = "机器人地图名最多 64 字符") String robotMapName
) {
}
