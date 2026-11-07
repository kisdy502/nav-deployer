package com.agv.navdeployer.task.domain.movetask;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@TableName("move_task")
public class MoveTask {

    public static final String TYPE_TO_WAYPOINT = "TO_WAYPOINT";
    public static final String TYPE_GOAL = "GOAL";
    public static final String TYPE_FOLLOW_PATH = "FOLLOW_PATH";
    public static final String TYPE_FREE = "FREE";

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_DISPATCHED = "DISPATCHED";
    public static final String STATUS_EXECUTING = "EXECUTING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String taskNo;

    private String taskType;

    private String status;

    private Long pointId;

    private Long waypointId;

    private Long pathId;

    private Double goalX;

    private Double goalY;

    private Double goalTheta;

    private Double currentX;

    private Double currentY;

    private Double currentTheta;

    private String agvState;

    private String errorMessage;

    private String goalId;

    private String source;

    private Integer segmentSeq;

    private Integer segmentTotal;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private LocalDateTime finishedAt;

    public boolean checkTerminal() {
        return STATUS_SUCCEEDED.equals(status) || STATUS_FAILED.equals(status)
                || STATUS_CANCELLED.equals(status) || STATUS_TIMEOUT.equals(status);
    }

    /**
     * 下发一段指令后回填目标句柄与段序；未进入执行态时置 DISPATCHED
     */
    public void markDispatchedGoal(String goalId, int segmentSeq) {
        this.goalId = goalId;
        this.segmentSeq = segmentSeq;
        if (!STATUS_EXECUTING.equals(status)) {
            this.status = STATUS_DISPATCHED;
        }
    }

    /**
     * @return 首次从 DISPATCHED 进入 EXECUTING 返回 true（用于触发落库与事件推送）
     */
    public boolean markExecutingIfDispatched() {
        if (STATUS_DISPATCHED.equals(status)) {
            this.status = STATUS_EXECUTING;
            return true;
        }
        return false;
    }

    public void markFinished(String status, String errorMessage) {
        this.status = status;
        if (errorMessage != null && !errorMessage.isBlank()) {
            this.errorMessage = errorMessage.length() > 500 ? errorMessage.substring(0, 500) : errorMessage;
        }
        this.finishedAt = LocalDateTime.now();
    }
}
