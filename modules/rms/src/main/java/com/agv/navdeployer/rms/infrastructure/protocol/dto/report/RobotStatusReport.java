package com.agv.navdeployer.rms.infrastructure.protocol.dto.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.time.OffsetDateTime;
import java.util.List;

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
