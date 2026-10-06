package com.agv.navdeployer.rms.task;

/**
 * RMS 任务状态（wire 名对齐 qcrobotmock，action_status 码对齐 RobotTask.actionStatusCode）。
 */
public enum RmsTaskStatus {

    ACCEPTED("accepted", 1),
    RUNNING("running", 2),
    PAUSED("paused", 2),
    COMPLETED("completed", 3),
    FAILED("failed", 4),
    ABORTED("aborted", 5),
    CANCELED("canceled", 6);

    private final String wireName;
    private final int actionStatusCode;

    RmsTaskStatus(String wireName, int actionStatusCode) {
        this.wireName = wireName;
        this.actionStatusCode = actionStatusCode;
    }

    public String wireName() {
        return wireName;
    }

    public int actionStatusCode() {
        return actionStatusCode;
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == ABORTED || this == CANCELED;
    }

    /** 兼容 RMS 侧的旧拼写别名。 */
    public static RmsTaskStatus fromWire(String value) {
        if (value == null) {
            return ACCEPTED;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "running" -> RUNNING;
            case "paused" -> PAUSED;
            case "completed", "success" -> COMPLETED;
            case "failed", "failure" -> FAILED;
            case "aborted", "abort" -> ABORTED;
            case "canceled", "cancelled", "stopped" -> CANCELED;
            default -> ACCEPTED;
        };
    }
}
