package com.agv.navdeployer.vo;

import com.agv.navdeployer.service.MapModeStateMachine;

import java.time.Instant;

/**
 * 地图模式任务（切图，P3 扩展建图/保存，重定位）视图。
 * 服务响应仅代表已受理；终态以机器人定位操作记录（operation_id）为权威，
 * 旧版机器人（无 operation_id）退化为 /agv/status mode 流转判定。
 */
public record MapModeTaskVO(
        Long id,
        String type,
        String status,
        Long navMapId,
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
                machine.getNavMapId(),
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
