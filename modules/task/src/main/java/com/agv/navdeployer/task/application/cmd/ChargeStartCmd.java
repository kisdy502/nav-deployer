package com.agv.navdeployer.task.application.cmd;

public record ChargeStartCmd(String source,
                             Long waypointId,
                             Double x,
                             Double y,
                             Double yaw,
                             Double targetBatteryPct) {

    public static ChargeStartCmd manual(Long waypointId, Double x, Double y, Double yaw, Double targetBatteryPct) {
        return new ChargeStartCmd("manual", waypointId, x, y, yaw, targetBatteryPct);
    }
}
