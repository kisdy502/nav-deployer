package com.agv.navdeployer.vo;

import com.agv.navdeployer.entity.MoveTask;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MoveTaskVO {

    private Long id;

    private String taskNo;

    private String taskType;

    private String status;

    private Long pointId;

    private Long pathId;

    private Double goalX;

    private Double goalY;

    private Double goalTheta;

    private Double currentX;

    private Double currentY;

    private Double currentTheta;

    private String agvState;

    private Integer segmentSeq;

    private Integer segmentTotal;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime finishedAt;

    public static MoveTaskVO from(MoveTask task) {
        return new MoveTaskVO(task.getId(), task.getTaskNo(), task.getTaskType(), task.getStatus(),
                task.getPointId(), task.getPathId(),
                task.getGoalX(), task.getGoalY(), task.getGoalTheta(),
                task.getCurrentX(), task.getCurrentY(), task.getCurrentTheta(),
                task.getAgvState(), task.getSegmentSeq(), task.getSegmentTotal(), task.getErrorMessage(),
                task.getCreatedAt(), task.getUpdatedAt(), task.getFinishedAt());
    }
}
