package com.agv.navdeployer.rms.protocol.keys;

import java.util.Arrays;
import java.util.Optional;

/**
 * body API 段枚举（替代 mock 里的字符串 switch）。
 * 枚举覆盖 mock 声明的全部 queryable；{@link #supported} 标记本上位机是否实现，
 * 未实现的段统一回复 501，RMS 可感知能力差异。
 */
public enum BodySegment {

    STATUS_QUERY("status/query", true),
    LOG("log", false),
    CONFIG("config", true),
    MODE_SET("mode/set", false),
    JOINT_STATES("joint-states", false),
    CAMERA("camera", false),
    AGV_RELOCATE("agv/relocate", false),
    START_ACTIVITY("start-activity", false),

    TASK_TEMPLATE_ADD("task_template/add", false),
    TASK_TEMPLATE_QUERY("task_template/query", true),
    TASK_TEMPLATE_DELETE("task_template/delete", false),

    TASK_ADD("task/add", true),
    TASK_DELETE("task/delete", true),
    TASK_START("task/start", true),
    TASK_PAUSE("task/pause", true),
    /** RMS 历史拼写兼容（真实存在过） */
    TASK_PAUSE_TYPO("task/puase", true),
    TASK_RESUME("task/resume", true),
    TASK_STOP("task/stop", true),
    TASK_STATUS("task/status", true),

    RESULT_FILES_UPLOAD_START("task/result_files_upload_start", false);

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

    /** mock 声明的全量 exact queryable 段（保持与 RMS 探测行为兼容）。 */
    public static String[] allExactSegments() {
        return Arrays.stream(values()).map(BodySegment::segment).toArray(String[]::new);
    }

    /** mock 声明的全量通配 queryable 段（task/{id}/xxx）。 */
    public static String[] allWildcardSegments() {
        return new String[]{"task/*/pause", "task/*/puase", "task/*/resume", "task/*/stop"};
    }
}
