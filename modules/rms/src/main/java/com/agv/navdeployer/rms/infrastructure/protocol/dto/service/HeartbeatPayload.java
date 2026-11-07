package com.agv.navdeployer.rms.infrastructure.protocol.dto.service;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HeartbeatPayload(
        String apiVersion,
        String robotCode,
        String robotSn,
        String robotName,
        String robotType,
        String ip
) {
}
