package com.agv.navdeployer.rms.protocol.dto.report;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 状态上报信封（对齐 RMS 日志里真机的实际格式）：
 * {"data": {模块化状态}, "serial_num": 序列号, "timestamp": epoch_ms}
 * data 为 {@link BodyFullStatusData}（真机模块集）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusReportEnvelope(
        Object data,
        String serialNum,
        String timestamp
) {
}
