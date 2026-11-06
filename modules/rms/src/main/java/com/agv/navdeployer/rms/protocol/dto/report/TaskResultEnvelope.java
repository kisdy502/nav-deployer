package com.agv.navdeployer.rms.protocol.dto.report;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskResultEnvelope(
        int status,
        TaskResultData data,
        long timestamp
) {
    public static TaskResultEnvelope of(TaskResultData data) {
        return new TaskResultEnvelope(0, data, Instant.now().toEpochMilli());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskResultData(
            String taskId,
            String actionId,
            String actionType,
            int actionStatus,
            String msg,
            Map<String, Object> resultReport
    ) {
    }
}
