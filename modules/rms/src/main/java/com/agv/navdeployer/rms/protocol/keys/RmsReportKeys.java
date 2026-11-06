package com.agv.navdeployer.rms.protocol.keys;

public final class RmsReportKeys {

    private final RobotKeyContext ctx;

    public RmsReportKeys(String prefix, String robotType, String robotCode) {
        this.ctx = new RobotKeyContext(prefix, robotType, robotCode);
    }

    public String bodyStatusReport() {
        return ctx.bodyApiBase() + "/status/report";
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
