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
@TableName("move_task_segment")
public class MoveTaskSegment {

    public static final String STATUS_EXECUTING = "EXECUTING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long taskId;
    private Integer seq;
    private String sourceCode;
    private String targetCode;
    private Double targetX;
    private Double targetY;
    private String edgeType;
    private Double maxSpeed;
    private String status;
    private String message;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
