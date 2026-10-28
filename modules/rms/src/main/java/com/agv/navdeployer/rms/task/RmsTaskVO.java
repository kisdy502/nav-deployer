package com.agv.navdeployer.rms.task;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * RMS 任务 VO（任务管理页 REST 载体，接口协议 snake_case）。
 * 时间从 Instant 转 LocalDateTime（与 MoveTaskVO 一致），parameters 原样透出。
 */
public record RmsTaskVO(
        String taskId,
        String actionId,
        String taskType,
        String taskTemplateType,
        String templateCode,
        String templateId,
        Map<String, Object> parameters,
        String status,
        int actionStatusCode,
        String message,
        Double destX,
        Double destY,
        Double destYaw,
        Long internalMoveTaskId,
        Long internalChargeTaskId,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {

    public static RmsTaskVO from(RmsTask task) {
        return new RmsTaskVO(
                task.taskId(),
                task.actionId(),
                task.taskType(),
                task.taskTemplateType(),
                task.templateCode(),
                task.templateId(),
                task.parameters(),
                task.status().name(),
                task.status().actionStatusCode(),
                task.message(),
                task.destination() == null ? null : task.destination().x(),
                task.destination() == null ? null : task.destination().y(),
                task.destination() == null ? null : task.destination().yaw(),
                task.internalMoveTaskId(),
                task.internalChargeTaskId(),
                toLocal(task.createdAt()),
                toLocal(task.startedAt()),
                toLocal(task.finishedAt()));
    }

    private static LocalDateTime toLocal(java.time.Instant instant) {
        return instant == null ? null
                : LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
    }
}
