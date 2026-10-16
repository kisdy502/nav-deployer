package com.agv.navdeployer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 从机器人导入地图：robotMapName 即机器人 maps_dir 三件套的文件名主干。 */
public record RobotMapImportDTO(
        @NotBlank(message = "机器人地图名不能为空") @Size(max = 128, message = "机器人地图名最多 128 字符") String robotMapName
) {
}
