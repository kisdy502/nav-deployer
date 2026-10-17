package com.agv.navdeployer.rms.protocol.dto.service;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 注册请求（对齐 qcrobotmock 的 buildRegisterPayload；空字段序列化时省略）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterRequest(
        String robotCode,
        String robotSn,
        String serialNo,
        String robotName,
        String ip,
        String model,
        String hardwareVersion,
        String firmwareVersion,
        String clientVersion,
        String manufacturer,
        String macAddress,
        String vendorTypeCode,
        String robotType,
        String robotTypeNo,
        String videoUrl,
        String apiVersion,
        String protocolVersion,
        List<String> capabilities,
        OffsetDateTime reportedAt
) {
}
