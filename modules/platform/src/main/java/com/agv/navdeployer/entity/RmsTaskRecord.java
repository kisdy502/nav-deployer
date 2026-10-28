package com.agv.navdeployer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** RMS 任务执行记录（跨重启历史；运行时权威状态在 RmsTaskRegistry 内存）。 */
@TableName("rms_task")
@Getter
@Setter
@NoArgsConstructor
public class RmsTaskRecord {

    /** RMS task_id（协议层唯一键，非自增，INPUT：由业务侧赋值。缺 @TableId 时 MP 不注入 updateById 等按主键方法） */
    @TableId(value = "task_id", type = IdType.INPUT)
    private String taskId;

    private String actionId;

    private String taskType;

    private String taskTemplateType;

    private String templateCode;

    private String templateId;

    /** 原始 parameters JSON 快照 */
    private String parameters;

    private String status;

    /** 终态原因（随 result_report 上报） */
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
