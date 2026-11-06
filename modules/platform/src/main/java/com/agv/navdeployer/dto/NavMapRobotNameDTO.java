package com.agv.navdeployer.dto;

import jakarta.validation.constraints.Size;

public record NavMapRobotNameDTO(
        @Size(max = 128, message = "机器人地图名最多 128 字符") String robotMapName
) {
}
