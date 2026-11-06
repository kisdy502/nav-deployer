package com.zioneer.robotmanager.robot.ota.domain.otarelease;

import com.zioneer.robotmanager.robot.domain.model.RobotVersionManifestEntry;

import java.util.List;

/**
 * 一次发布展开后的单个目标：机器人 ID、编码与它当时的当前版本清单。
 */
public record OtaTarget(
        Long id,
        String code,
        List<RobotVersionManifestEntry> currentManifest
) {
}
