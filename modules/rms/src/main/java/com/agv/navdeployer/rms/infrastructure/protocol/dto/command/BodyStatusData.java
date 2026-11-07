package com.agv.navdeployer.rms.infrastructure.protocol.dto.command;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

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
