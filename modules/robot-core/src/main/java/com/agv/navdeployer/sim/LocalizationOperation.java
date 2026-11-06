package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

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

    public boolean isTerminal() {
        return STATE_SUCCEEDED.equals(state) || STATE_FAILED.equals(state)
                || STATE_TIMED_OUT.equals(state) || finished;
    }

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
