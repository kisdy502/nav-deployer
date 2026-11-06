package com.agv.navdeployer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@TableName("rms_task")
@Getter
@Setter
@NoArgsConstructor
public class RmsTaskRecord {

    @TableId(value = "task_id", type = IdType.INPUT)
    private String taskId;

    private String actionId;

    private String taskType;

    private String taskTemplateType;

    private String templateCode;

    private String templateId;

    private String parameters;

    private String status;

    private String message;

    private Double destX;

    private Double destY;

    private Double destYaw;

    private String destTopoName;

    private Long internalMoveTaskId;

    private Long internalChargeTaskId;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
