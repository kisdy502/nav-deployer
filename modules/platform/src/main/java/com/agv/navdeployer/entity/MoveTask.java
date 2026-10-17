package com.agv.navdeployer.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 移动任务：TO_POINT / GOAL / FOLLOW_PATH，以 command_id 与 follow_edge result 对账。 */
@TableName("move_task")
@Getter
@Setter
@NoArgsConstructor
public class MoveTask {

    public static final String TYPE_TO_POINT = "TO_POINT";
    public static final String TYPE_GOAL = "GOAL";
    public static final String TYPE_FOLLOW_PATH = "FOLLOW_PATH";

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_DISPATCHED = "DISPATCHED";
    public static final String STATUS_EXECUTING = "EXECUTING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** follow_edge 的 command_id（uuid），全局唯一。 */
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

    private String errorMessage;

    /** rosbridge send_action_goal 的 id（当前段）。 */
    private String goalId;

    /** FOLLOW_PATH 进度：当前段 / 总段数（从 1 开始）。 */
    private Integer segmentSeq;

    private Integer segmentTotal;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private LocalDateTime finishedAt;
}
