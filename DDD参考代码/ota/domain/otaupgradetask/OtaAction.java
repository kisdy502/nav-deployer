package com.zioneer.robotmanager.robot.ota.domain.otaupgradetask;

import java.time.Instant;

public record OtaAction(
        Long actionId,
        OtaActionStatus actionStatus,
        String failureDetail,
        Instant lastReportedAt
) {
}
