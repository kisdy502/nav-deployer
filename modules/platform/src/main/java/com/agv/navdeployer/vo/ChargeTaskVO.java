package com.agv.navdeployer.vo;

import java.time.LocalDateTime;

/**
 * 充电任务 VO（REST / SSE "charge" 事件统一载体，接口协议 snake_case）。
 * status 阶段见 ChargeService.Status：到桩导航 → 开始充电（服务+遥测确认）→ 充电中 → 终态。
 */
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
