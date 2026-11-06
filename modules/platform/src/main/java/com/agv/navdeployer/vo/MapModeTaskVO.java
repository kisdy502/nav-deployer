package com.agv.navdeployer.vo;

import com.agv.navdeployer.service.MapModeStateMachine;

import java.time.Instant;

public record MapModeTaskVO(
        Long id,
        String type,
        String status,
        Long mapIdV2,
        String mapName,
        String robotMapName,
        String errorMessage,
        String operationId,
        String stage,
        String detailMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt
) {
    public static MapModeTaskVO from(MapModeStateMachine machine) {
        return new MapModeTaskVO(
                machine.getId(),
                machine.getType().name(),
                machine.getStatus().name(),
                machine.getV2MapId(),
                machine.getMapName(),
                machine.getRobotMapName(),
                machine.getErrorMessage(),
                machine.getOperationId(),
                machine.getStage(),
                machine.getDetailMessage(),
                machine.getCreatedAt(),
                machine.getUpdatedAt(),
                machine.getFinishedAt());
    }
}
