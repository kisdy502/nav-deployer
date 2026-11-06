package com.agv.navdeployer.rms.protocol.dto.command;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

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
