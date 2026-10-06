package com.agv.navdeployer.rms.protocol.keys;

/**
 * 【C 模式：RMS 查询，本机 queryable 应答】的 key 集合与解析工具。
 * 本类产出的 key 都配合 ZenohChannel.declareQueryable 使用——RMS 发 query、我们回 BodyReply。
 * 段清单见 {@link BodySegment}（含未支持段的 501 标记）。
 */
public final class RmsCommandKeys {

    private final RobotKeyContext ctx;

    public RmsCommandKeys(String prefix, String robotType, String robotCode) {
        this.ctx = new RobotKeyContext(prefix, robotType, robotCode);
    }

    /** 指令端点完整 key：.../api/v1/{segment}（segment 可含 task 通配段）。 */
    public String bodyKey(String segment) {
        return ctx.bodyApiBase() + "/" + KeyStrings.require(KeyStrings.normalize(segment, null), "segment");
    }

    /** 需要声明的全量 exact 段（保持与 RMS 探测行为兼容）。 */
    public static String[] exactSegments() {
        return BodySegment.allExactSegments();
    }

    /** 需要声明的全量通配段（task/{id}/pause|resume|stop）。 */
    public static String[] wildcardSegments() {
        return BodySegment.allWildcardSegments();
    }

    /** 从实际收到的 key 提取 body 段（.../api/v1/ 之后的部分）；解析失败返回空串。 */
    public static String extractBodySegment(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        int idx = key.lastIndexOf("/api/v1/");
        return idx < 0 ? "" : key.substring(idx + "/api/v1/".length());
    }

    /** 从 task/{id}/{action} 段里提取 task_id；非该形态返回 null。 */
    public static String extractTaskIdFromSegment(String segment) {
        if (segment == null) {
            return null;
        }
        String[] parts = segment.split("/");
        if (parts.length == 3 && "task".equals(parts[0])
                && ("pause".equals(parts[2]) || "puase".equals(parts[2])
                || "resume".equals(parts[2]) || "stop".equals(parts[2]))) {
            return parts[1];
        }
        return null;
    }
}
