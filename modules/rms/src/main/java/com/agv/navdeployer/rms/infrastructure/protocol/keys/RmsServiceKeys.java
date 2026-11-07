package com.agv.navdeployer.rms.infrastructure.protocol.keys;

public final class RmsServiceKeys {

    public static final String DEFAULT_REGISTER_KEY = "zioneer/robot-management-service/api/v1/robot-mgr/register";

    private final RobotKeyContext ctx;
    private final String registerKey;

    public RmsServiceKeys(String prefix, String robotType, String robotCode, String registerKey) {
        this.ctx = new RobotKeyContext(prefix, robotType, robotCode);
        this.registerKey = KeyStrings.normalize(registerKey, DEFAULT_REGISTER_KEY);
    }

    public String register() {
        return registerKey;
    }

    public String heartbeatQuery() {
        return ctx.bodyApiBase() + "/heartbeat";
    }
}
