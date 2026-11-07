package com.agv.navdeployer.rms.infrastructure.protocol.keys;

record RobotKeyContext(String prefix, String robotType, String robotCode) {

    private static final String DEFAULT_PREFIX = "zioneer";
    private static final String API_VERSION = "v1";

    RobotKeyContext(String prefix, String robotType, String robotCode) {
        this.prefix = KeyStrings.normalize(prefix, DEFAULT_PREFIX);
        this.robotType = KeyStrings.require(robotType, "robotType");
        this.robotCode = robotCode == null ? "" : robotCode.trim();
    }

    String bodyApiBase() {
        // 云端 body 前缀硬编码 zioneer，与 robot_key_prefix 配置无关，勿改为 prefix
        return "zioneer/" + robotType + "/robot/" + robotCode + "/api/" + API_VERSION;
    }

    String robotScopedBase() {
        return prefix + "/robot/" + robotCode;
    }
}

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
