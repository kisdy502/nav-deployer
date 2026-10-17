package com.agv.navdeployer.dto;

import jakarta.validation.constraints.Size;

/** 切图请求：robotMapName 可省略，缺省用地图档案里的 robot_map_name（再回退 map_name）。 */
public record MapSwitchDTO(
        @Size(max = 64, message = "机器人地图名最多 64 字符") String robotMapName
) {
}
