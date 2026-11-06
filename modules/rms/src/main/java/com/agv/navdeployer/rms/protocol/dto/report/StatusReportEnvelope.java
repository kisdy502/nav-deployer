package com.agv.navdeployer.rms.protocol.dto.report;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusReportEnvelope(
        Object data,
        String serialNum,
        String timestamp
) {
}
