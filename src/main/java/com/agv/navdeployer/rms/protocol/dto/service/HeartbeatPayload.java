package com.agv.navdeployer.rms.protocol.dto.service;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 心跳 payload（对齐 RMS 日志里真机 RB_20260924_00026 的实际格式）：
 * 简单身份信息，不是完整状态报告。RMS 的 HeartbeatQueryHandler 只读这些字段。
 */
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
