package com.agv.navdeployer.task.application.dto;

import java.time.LocalDateTime;

public record MoveTaskDTO(Long id,
                          String taskNo,
                          String taskType,
                          String status,
                          Long pointId,
                          Long waypointId,
                          Long pathId,
                          Double goalX,
                          Double goalY,
                          Double goalTheta,
                          Double currentX,
                          Double currentY,
                          Double currentTheta,
                          String agvState,
                          Integer segmentSeq,
                          Integer segmentTotal,
                          String errorMessage,
                          String source,
                          String routeSummary,
                          LocalDateTime createdAt,
                          LocalDateTime updatedAt,
                          LocalDateTime finishedAt) {
}
