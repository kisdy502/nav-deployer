package com.agv.navdeployer.rms.protocol.dto.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 心跳 / 状态上报报文（对齐 qcrobotmock 的 buildRuntimeReportPayload 字段清单；
 * 心跳额外携带 heartbeat_at，通过 {@link JsonUnwrapped} 复用同一结构）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RobotStatusReport(
        String robotCode,
        String robotSn,
        String serialNo,
        String onlineStatus,
        String location,
        Double battery,
        Double mileageKm,
        String currentMapCode,
        String currentMapName,
        String dispatchMode,
        String controlStatus,
        String exceptionStatus,
        String videoStatus,
        String chassisMode,
        String armMode,
        Boolean isCharging,
        Boolean isHoming,
        Boolean isLifted,
        String mapName,
        Position position,
        List<String> compatibleChargers,
        List<String> rmfCompatibleChargers,
        String bodyTaskId,
        String bodyTaskStatus,
        OffsetDateTime reportedAt
) {
    public HeartbeatReport withHeartbeatAt(OffsetDateTime heartbeatAt) {
        return new HeartbeatReport(robotCode, robotSn, serialNo, onlineStatus, location, battery, mileageKm,
                currentMapCode, currentMapName, dispatchMode, controlStatus, exceptionStatus, videoStatus,
                chassisMode, armMode, isCharging, isHoming, isLifted, mapName, position, compatibleChargers,
                rmfCompatibleChargers, bodyTaskId, bodyTaskStatus, reportedAt, heartbeatAt);
    }

    public record Position(double x, double y, double yaw) {
    }

    /** 心跳 = 状态报文 + heartbeat_at（字段平铺，与 mock 一致）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HeartbeatReport(
            String robotCode, String robotSn, String serialNo, String onlineStatus, String location,
            Double battery, Double mileageKm, String currentMapCode, String currentMapName,
            String dispatchMode, String controlStatus, String exceptionStatus, String videoStatus,
            String chassisMode, String armMode, Boolean isCharging, Boolean isHoming, Boolean isLifted,
            String mapName, Position position, List<String> compatibleChargers,
            List<String> rmfCompatibleChargers, String bodyTaskId, String bodyTaskStatus,
            OffsetDateTime reportedAt, OffsetDateTime heartbeatAt
    ) {
    }
}
