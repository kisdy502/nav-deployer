package com.agv.navdeployer.rms.protocol.keys;

public final class RmsCommandKeys {

    private final RobotKeyContext ctx;

    public RmsCommandKeys(String prefix, String robotType, String robotCode) {
        this.ctx = new RobotKeyContext(prefix, robotType, robotCode);
    }

    public String bodyKey(String segment) {
        return ctx.bodyApiBase() + "/" + KeyStrings.require(KeyStrings.normalize(segment, null), "segment");
    }

    public String bodyKeyBase() {
        return ctx.bodyApiBase();
    }

    public static String[] exactSegments() {
        return BodySegment.allExactSegments();
    }

    public static String[] wildcardSegments() {
        return BodySegment.allWildcardSegments();
    }

    public static String extractBodySegment(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        int idx = key.lastIndexOf("/api/v1/");
        return idx < 0 ? "" : key.substring(idx + "/api/v1/".length());
    }

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
