package com.agv.navdeployer.rms.infrastructure.protocol.keys;

import java.util.Arrays;
import java.util.Optional;

public enum BodySegment {

    STATUS_QUERY("status/query", true),
    LOG("log", false),
    CONFIG("config", true),
    CONFIG_GET("config/get", true),
    CONFIG_EDIT("config/edit", true),
    POSE_QUERY("pose/query", true),
    MODE_SET("mode/set", true),
    MODE_GET("mode/get", true),
    JOINT_STATES("joint-states", false),
    CAMERA("camera", false),
    AGV_RELOCATE("agv/relocate", true),
    START_ACTIVITY("start-activity", false),

    START_MAPPING("start_mapping", true),
    STOP_MAPPING("stop_mapping", true),
    MAPPING_STATUS("mapping/status", true),
    MAPPING_LIST("mapping/list", true),
    MAPPING_CHANGE("mapping/change", true),
    MAPPING_GET_CURRENT("mapping/get_current", true),
    MAPPING_DELETE("mapping/delete", true),
    MAPPING_GET("mapping/get", true),
    MAPPING_DOWNLOAD("mapping/download", true),

    TASK_TEMPLATE_ADD("task_template/add", true),
    TASK_TEMPLATE_QUERY("task_template/query", true),
    TASK_TEMPLATE_DELETE("task_template/delete", true),

    TASK_ADD("task/add", true),
    TASK_DELETE("task/delete", true),
    TASK_START("task/start", true),
    TASK_PAUSE("task/pause", true),
    // RMS 协议拼写兼容：云端真实下发过 task/puase，勿改
    TASK_PAUSE_TYPO("task/puase", true),
    TASK_RESUME("task/resume", true),
    TASK_STOP("task/stop", true),
    TASK_STATUS("task/status", true),

    RESULT_FILES_UPLOAD_START("task/result_files_upload_start", true),

    LOG_COLLECT("log/collect", true),

    TEACH_CAPTURE("teach/capture", true);

    private final String segment;
    private final boolean supported;

    BodySegment(String segment, boolean supported) {
        this.segment = segment;
        this.supported = supported;
    }

    public String segment() {
        return segment;
    }

    public boolean supported() {
        return supported;
    }

    public static Optional<BodySegment> fromSegment(String value) {
        if (value == null || value.isBlank() || value.contains("*")) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(candidate -> candidate.segment.equals(value))
                .findFirst();
    }

    public static String[] allExactSegments() {
        return Arrays.stream(values()).map(BodySegment::segment).toArray(String[]::new);
    }

    public static String[] allWildcardSegments() {
        return new String[]{"task/*/pause", "task/*/puase", "task/*/resume", "task/*/stop"};
    }
}
