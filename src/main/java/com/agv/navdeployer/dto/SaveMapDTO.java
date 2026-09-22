package com.agv.navdeployer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 保存建图请求：mapName 即机器人侧地图名（三件套文件名主干，也作为档案名，可随后改名）。 */
public record SaveMapDTO(
        @NotBlank(message = "地图名不能为空") @Size(max = 64, message = "地图名最多 64 字符") String mapName
) {
}
