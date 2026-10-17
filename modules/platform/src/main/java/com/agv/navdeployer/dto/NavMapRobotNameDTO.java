package com.agv.navdeployer.dto;

import jakarta.validation.constraints.Size;

/** 设置机器人侧地图名；传空串清除（切图时回退用 mapName）。 */
public record NavMapRobotNameDTO(
        @Size(max = 128, message = "机器人地图名最多 128 字符") String robotMapName
) {
}
