package com.agv.navdeployer.rms.infrastructure.protocol.keys;

public final class RmsReportKeys {

    private final RobotKeyContext ctx;

    public RmsReportKeys(String prefix, String robotType, String robotCode) {
        this.ctx = new RobotKeyContext(prefix, robotType, robotCode);
    }

    public String bodyStatusReport() {
        return ctx.bodyApiBase() + "/status/report";
    }

    /**
     * 高频位置上报（1Hz 基线 + 位移/角度超阈值立即上报）。
     */
    public String positionReport() {
        return ctx.bodyApiBase() + "/position/report";
    }

    public String legacyStatusReport() {
        return ctx.robotScopedBase() + "/status/report";
    }

    public String legacyHeartbeat() {
        return ctx.robotScopedBase() + "/heartbeat";
    }

    public String taskResultReport(String taskId) {
        return ctx.bodyApiBase() + "/task/result_report";
    }
}
