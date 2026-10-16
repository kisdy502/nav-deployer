package com.agv.navdeployer.rms.protocol.keys;

/**
 * key 拼接共用的机器人身份上下文（prefix/register_key/robot_type/robot_code，来自 RmsProperties）。
 * 包私有：三个方向的 keys 类（Report/Service/Command）共享 base 拼接与段清洗工具。
 */
record RobotKeyContext(String prefix, String robotType, String robotCode) {

    private static final String DEFAULT_PREFIX = "zioneer";
    private static final String API_VERSION = "v1";

    RobotKeyContext(String prefix, String robotType, String robotCode) {
        this.prefix = KeyStrings.normalize(prefix, DEFAULT_PREFIX);
        this.robotType = KeyStrings.require(robotType, "robotType");
        // robotCode 允许为空：初始 keys 用配置值构造（可能为空），
        // 注册后 Session 会用 RMS 分配的 code 重建全部 keys
        this.robotCode = robotCode == null ? "" : robotCode.trim();
    }

    /**
     * body API 基址：zioneer/{robot_type}/robot/{robot_code}/api/v1
     * 前缀固定 "zioneer"（RMS 的 RobotZenohConstant.buildBodyApiPrefix），
     * 不受 robot_key_prefix 影响——那个只管 legacy key。
     * 曾经的 bug：robot_key_prefix 改为 robot-management/robots 后 body API
     * key 全部错位，心跳/状态/任务指令全部到不了 RMS（注册 key 独立所以没暴露）。
     */
    String bodyApiBase() {
        return "zioneer/" + robotType + "/robot/" + robotCode + "/api/" + API_VERSION;
    }

    /** legacy 基址：{prefix}/robot/{robot_code}（RMS 的 DEFAULT_ROBOT_KEY_PREFIX） */
    String robotScopedBase() {
        return prefix + "/robot/" + robotCode;
    }
}

/** key 段清洗工具。 */
final class KeyStrings {

    private KeyStrings() {
    }

    static String normalize(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() && fallback != null) {
            normalized = fallback.trim();
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    static String require(String value, String field) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
