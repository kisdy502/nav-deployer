package com.agv.navdeployer.vo;

import com.agv.navdeployer.service.MapModeStateMachine;

import java.time.Instant;

/**
 * 地图模式任务（切图，P3 扩展建图/保存）视图。
 * 服务响应仅代表已受理，终态由 /agv/status 的 mode 流转驱动。
 */
public record MapModeTaskVO(
        Long id,
        String type,
        String status,
        Long navMapId,
        String mapName,
        String robotMapName,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt
) {
    public static MapModeTaskVO from(MapModeStateMachine machine) {
        return new MapModeTaskVO(
                machine.getId(),
                machine.getType().name(),
                machine.getStatus().name(),
                machine.getNavMapId(),
                machine.getMapName(),
                machine.getRobotMapName(),
                machine.getErrorMessage(),
                machine.getCreatedAt(),
                machine.getUpdatedAt(),
                machine.getFinishedAt());
    }
}
