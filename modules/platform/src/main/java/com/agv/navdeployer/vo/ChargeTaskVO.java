package com.agv.navdeployer.vo;

import java.time.LocalDateTime;

public record ChargeTaskVO(
        Long id,
        String source,
        String status,
        Long waypointId,
        Double x,
        Double y,
        Double yaw,
        Double targetBatteryPct,
        Double batteryAtStart,
        Double batteryNow,
        String message,
        Long internalMoveTaskId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime finishedAt
) {
}
