package com.agv.navdeployer.rms.infrastructure.protocol.dto.report;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BodyFullStatusData(
        AgvStatus agvStatus,
        List<Battery> battery,
        List<Map<String, Object>> exceptions,
        Map<String, Object> inspection,
        MapInfo mapInfo,
        Memory memory,
        List<Mode> mode,
        List<Odometry> odometry,
        Position position,
        RobotInfo robotInfo,
        SystemInfo system,
        List<Temperature> temperature,
        Map<String, Object> arm
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgvStatus(
            String status, Integer statusCode, Boolean success, Integer workMode, String updateTime) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Battery(
            String batteryId, Double capacity, Double current, Double designCapacity,
            Integer id, Boolean isCharging, Double level, Double percentage,
            String position, Integer powerSupplyStatus, String updateTime, Double vol) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MapInfo(String currentMapName, String updateTime) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Memory(Long availableKb, Long totalKb, Long usedKb, String updateTime) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Mode(String mode, String updateTime) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Odometry(
            String position, Double theta, String updateTime,
            Double vx, Double vy, Double w, Double x, Double y) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Position(
            String frameId, Double qw, Double qx, Double qy, Double qz, String updateTime,
            Double x, Double y, Double z, Double yaw) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RobotInfo(String aliasName, String updateTime) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SystemInfo(
            String arch, Integer cpuCount, String cpuUseage, String hostname, String os,
            Long uptimeSeconds, String updateTime) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Temperature(String batteryId, String position, Double temperature, String updateTime) {
    }
}
