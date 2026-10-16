package com.agv.navdeployer.rms.protocol.dto.command;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 任务信息（task/add、task/start 的回复 data 与 task/status 查询结果，
 * 对齐 qcrobotmock 的 RobotTask.toBodyMap）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskInfoReport(
        String taskId,
        String actionId,
        String taskType,
        String taskTemplateType,
        String templateCode,
        String templateId,
        String taskStatus,
        int actionStatus,
        Map<String, Object> parameters
) {
}
