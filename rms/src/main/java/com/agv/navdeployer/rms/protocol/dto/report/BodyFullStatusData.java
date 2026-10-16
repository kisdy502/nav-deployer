package com.agv.navdeployer.rms.protocol.dto.report;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 状态上报 body data 部分（对齐真机 status/report 实际报文的模块集）：
 * agv_status / battery / exceptions / inspection / map_info / memory /
 * mode / odometry / position / robot_info / system / temperature。
 * 字段命名依赖全局 SNAKE_CASE 策略（camelCase → snake_case），
 * 与 RMS RobotBodyStatus 的解析字段一一对应（inspection/exceptions 真机有、RMS 忽略）。
 */
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
        List<Temperature> temperature
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgvStatus(
            String status, Integer statusCode, Boolean success, Integer workMode, String updateTime) {
    }

    /** 双电池包（真机同 ID 两组：id=1/2，百分比一致） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Battery(
            String batteryId, Double capacity, Double current, Double designCapacity,
            Integer id, Boolean isCharging, Double level, Double percentage,
            String position, Integer powerSupplyStatus, String updateTime, Double vol) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MapInfo(String currentMapName, String updateTime) {
    }

    /** 宿主机真实内存（HostStats 读取） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Memory(Long availableKb, Long totalKb, Long usedKb, String updateTime) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Mode(String mode, String updateTime) {
    }

    /** odom 系速度/位姿（vx vy w 单位 m/s、rad/s） */
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

    /** 宿主机真实系统信息 + CPU 占用 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SystemInfo(
            String arch, Integer cpuCount, String cpuUseage, String hostname, String os,
            Long uptimeSeconds, String updateTime) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Temperature(String batteryId, String position, Double temperature, String updateTime) {
    }
}
