package com.agv.navdeployer.rms.protocol.dto.command;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * status/query 的 data 部分（对齐 qcrobotmock 的 buildStatusData 默认模块集）。
 * parameters 为 RMS 真实下发的动态字典，用受控的 AnyGetter 透传，
 * 不在业务代码里手拼 Map。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BodyStatusData(
        AgvStatus agvStatus,
        PositionReport position,
        BatteryReport battery,
        String serialNum
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (agvStatus != null) map.put("agv_status", agvStatus);
        if (position != null) map.put("position", position);
        if (battery != null) map.put("battery", battery);
        if (serialNum != null) map.put("serial_num", serialNum);
        return map;
    }

    @JsonAnyGetter
    public Map<String, Object> any() {
        return toMap();
    }

    public record AgvStatus(String status, int statusCode, boolean success, String updateTime) {
    }

    public record PositionReport(
            String frameId, double x, double y, double z, double yaw,
            double qx, double qy, double qz, double qw, String updateTime) {
    }

    public record BatteryReport(boolean isCharging, double level, double soc, String updateTime) {
    }
}
