package com.agv.navdeployer.rms.task;

/** RMS 任务状态，action_status 严格对齐真机 TaskStatus。 */
public enum RmsTaskStatus {

    IDLE("idle", 0),
    RUNNING("running", 1),
    PAUSED("paused", 2),
    COMPLETED("completed", 3),
    FAILED("failed", 4),
    ABORTED("failed", 4),
    CANCELED("canceled", 5),
    BT_LOAD_FAILED("bt_load_failed", 6),
    PHOTO_NODE_FAILED("photo_node_failed", 10),
    ARM_NODE_FAILED("arm_node_failed", 11),
    RESULT_UPLOAD_FAILED("result_upload_failed", 12),
    AGV_NODE_FAILED("agv_node_failed", 13),
    AUDIO_NODE_FAILED("audio_node_failed", 14),
    LIFT_POLE_NODE_FAILED("lift_pole_node_failed", 15),
    LLM_NODE_FAILED("llm_node_failed", 16);

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
        return actionStatusCode >= 3;
    }

    public boolean failed() {
        return this != COMPLETED && this != CANCELED && terminal();
    }

    /** 兼容 RMS 侧的旧拼写别名。 */
    public static RmsTaskStatus fromWire(String value) {
        if (value == null) {
            return IDLE;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "idle", "accepted" -> IDLE;
            case "running" -> RUNNING;
            case "paused" -> PAUSED;
            case "completed", "success" -> COMPLETED;
            case "failed", "failure" -> FAILED;
            case "aborted", "abort" -> ABORTED;
            case "canceled", "cancelled", "stopped" -> CANCELED;
            case "bt_load_failed" -> BT_LOAD_FAILED;
            case "photo_node_failed" -> PHOTO_NODE_FAILED;
            case "arm_node_failed" -> ARM_NODE_FAILED;
            case "result_upload_failed" -> RESULT_UPLOAD_FAILED;
            case "agv_node_failed" -> AGV_NODE_FAILED;
            case "audio_node_failed" -> AUDIO_NODE_FAILED;
            case "lift_pole_node_failed" -> LIFT_POLE_NODE_FAILED;
            case "llm_node_failed" -> LLM_NODE_FAILED;
            default -> IDLE;
        };
    }
}
