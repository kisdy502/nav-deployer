package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

/** 旧版 nav_map 迁移到 v2 多楼层格式（含点位类型映射与路线曲线字段合并）。 */
public record MapMigrateDTO(
        @Size(max = 64) String mapName,
        @Size(max = 64) String modifiedBy) {
}
