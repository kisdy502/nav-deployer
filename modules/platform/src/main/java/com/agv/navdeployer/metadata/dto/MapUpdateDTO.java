package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

/** 地图元信息更新（重命名 / 机器人地图名）。 */
public record MapUpdateDTO(
        @Size(max = 64) String mapName,
        @Size(max = 128) String robotMapName,
        @Size(max = 64) String modifiedBy) {
}
