package com.zioneer.robotmanager.robot.ota.domain.otamanifest;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 目标清单中未在机器人当前清单达标的节点：key 不存在或版本不一致。
 */
@Schema(description = "清单核验未达标节点")
public record OtaManifestMismatch(
        @Schema(description = "清单项 key") String key,
        @Schema(description = "清单项名称，目标清单未填时为空") String name,
        @Schema(description = "目标版本") String targetVersion,
        @Schema(description = "机器人当前版本，未安装时为空") String currentVersion) {
}
