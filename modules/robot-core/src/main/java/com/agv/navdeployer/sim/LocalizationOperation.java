package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 定位操作记录（镜像 agv_bridge_v2_interfaces/msg/LocalizationOperation）。
 *
 * 机器人侧契约（docs/localization_operation_api.md）：
 * - load_map / relocalize 受理响应携带 operation_id，success=true 仅表示已受理；
 * - 最终结论只能看本记录的 finished / state（SUCCEEDED / FAILED / TIMED_OUT）；
 * - state=RUNNING 时 success=false 不代表失败；迟到的后台回调不会改写已终态的记录；
 * - elapsed_sec 为机器人单调时钟，终态后固定，可直接用于展示耗时。
 */
public record LocalizationOperation(
        String operationId,
        String operationType,
        String mapName,
        String state,
        String stage,
        boolean finished,
        boolean success,
        String message,
        Boolean poseInitialized,
        Boolean navigationReady,
        double elapsedSec,
        Instant receivedAt
) {

    public static final String TYPE_LOAD_MAP = "LOAD_MAP";
    public static final String TYPE_RELOCALIZE = "RELOCALIZE";

    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_SUCCEEDED = "SUCCEEDED";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_TIMED_OUT = "TIMED_OUT";

    /** 从 rosbridge publish 消息的 msg 节点解析；operation_id 缺失（旧版机器人）返回 null。 */
    public static LocalizationOperation fromMessage(JsonNode msg) {
        if (msg == null || msg.isMissingNode()) {
            return null;
        }
        String operationId = text(msg, "operation_id");
        if (operationId == null || operationId.isBlank()) {
            return null;
        }
        return new LocalizationOperation(
                operationId,
                text(msg, "operation_type"),
                text(msg, "map_name"),
                text(msg, "state"),
                text(msg, "stage"),
                msg.path("finished").asBoolean(false),
                msg.path("success").asBoolean(false),
                text(msg, "message"),
                nullableBool(msg, "pose_initialized"),
                nullableBool(msg, "navigation_ready"),
                msg.path("elapsed_sec").asDouble(0.0),
                Instant.now());
    }

    /** 已有终态结论（SUCCEEDED / FAILED / TIMED_OUT）。 */
    public boolean isTerminal() {
        return STATE_SUCCEEDED.equals(state) || STATE_FAILED.equals(state)
                || STATE_TIMED_OUT.equals(state) || finished;
    }

    /** 失败类终态的机器人侧原因（TIMED_OUT 归一化为超时文案）。 */
    public String failureReason() {
        if (STATE_TIMED_OUT.equals(state)) {
            return message == null || message.isBlank()
                    ? "机器人侧定位操作超时（TIMED_OUT）" : "机器人侧超时: " + message;
        }
        return message == null || message.isBlank() ? "机器人侧执行失败" : message;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static Boolean nullableBool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asBoolean();
    }
}
