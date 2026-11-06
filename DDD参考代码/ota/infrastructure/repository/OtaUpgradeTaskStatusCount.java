package com.zioneer.robotmanager.robot.ota.infrastructure.repository;

import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaUpgradeTaskStatus;

/**
 * 按发布、状态分组统计的查询结果行。
 */
public record OtaUpgradeTaskStatusCount(
        Long releaseId,
        OtaUpgradeTaskStatus status,
        Long total
) {
}
