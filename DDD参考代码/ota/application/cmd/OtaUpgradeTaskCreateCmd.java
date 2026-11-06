package com.zioneer.robotmanager.robot.ota.application.cmd;

import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaManifestEntry;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageSnapshot;
import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaTargetType;

import java.util.List;

public record OtaUpgradeTaskCreateCmd(
        Long releaseId,
        OtaTargetType targetType,
        String targetId,
        OtaPackageSnapshot packageSnapshot,
        List<OtaManifestEntry> currentManifestSnapshot
) {
}
