package com.zioneer.robotmanager.robot.ota.domain.otarelease;

import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaUpgradeTaskStatus;

import java.util.Map;

/**
 * 发布进度：由所属升级任务的状态计数实时推导，不落库。
 */
public record OtaReleaseProgress(
        OtaReleaseStatus status,
        long total,
        long pending,
        long inProgress,
        long succeeded,
        long failed
) {

    public static OtaReleaseProgress of(Map<OtaUpgradeTaskStatus, Long> countsByStatus) {
        long pending = count(countsByStatus, OtaUpgradeTaskStatus.PENDING);
        long inProgress = count(countsByStatus, OtaUpgradeTaskStatus.IN_PROGRESS);
        long succeeded = count(countsByStatus, OtaUpgradeTaskStatus.COMPLETED);
        long failed = count(countsByStatus, OtaUpgradeTaskStatus.FAILED);
        return new OtaReleaseProgress(
                resolveStatus(pending, inProgress, succeeded, failed),
                pending + inProgress + succeeded + failed,
                pending,
                inProgress,
                succeeded,
                failed
        );
    }

    private static OtaReleaseStatus resolveStatus(long pending, long inProgress, long succeeded, long failed) {
        if (pending == 0 && inProgress == 0) {
            return OtaReleaseStatus.COMPLETED;
        }
        if (succeeded == 0 && failed == 0 && inProgress == 0) {
            return OtaReleaseStatus.PENDING;
        }
        return OtaReleaseStatus.IN_PROGRESS;
    }

    private static long count(Map<OtaUpgradeTaskStatus, Long> countsByStatus, OtaUpgradeTaskStatus status) {
        return countsByStatus.getOrDefault(status, 0L);
    }
}
