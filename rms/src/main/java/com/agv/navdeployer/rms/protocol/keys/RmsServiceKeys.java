package com.agv.navdeployer.rms.protocol.keys;

/**
 * 【B 模式：机器人主动查询，RMS 应答】的 key 集合。
 * 本类里的每个 key 都只配合 ZenohChannel.query 使用——发 query、等回复、校验应答。
 */
public final class RmsServiceKeys {

    public static final String DEFAULT_REGISTER_KEY = "zioneer/robot-management-service/api/v1/robot-mgr/register";

    private final RobotKeyContext ctx;
    private final String registerKey;

    public RmsServiceKeys(String prefix, String robotType, String robotCode, String registerKey) {
        this.ctx = new RobotKeyContext(prefix, robotType, robotCode);
        this.registerKey = KeyStrings.normalize(registerKey, DEFAULT_REGISTER_KEY);
    }

    /** 注册：会话建立后查询一次（重连后重做）。 */
    public String register() {
        return registerKey;
    }

    /** 心跳查询：.../api/v1/heartbeat（周期发起，RMS 应答即心跳存活凭证）。 */
    public String heartbeatQuery() {
        return ctx.bodyApiBase() + "/heartbeat";
    }
}
