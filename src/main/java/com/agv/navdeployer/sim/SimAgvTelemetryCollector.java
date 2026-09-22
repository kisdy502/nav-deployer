package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Locale;

/**
 * 把 rosbridge 推送的仿真 AGV 话题解析成 {@link SimAgvTelemetry}：
 * - /agv/status：state / battery / pose_initialized / active_command_id / active_node_id
 * - /tf：过滤 header.frame_id=map 且 child_frame_id=<agv_id>/base_link 的变换，解算 x/y/yaw
 * - /scan_1、/scan_2：2D 激光摘要（束数、有限测距 min/max）
 * - /odom（可选）：位姿 + 线/角速度
 *
 * 日志策略：每条消息都更新 telemetry，另按 summary-interval-ms 周期输出一条
 * INFO 摘要，保证"限流但可见"。
 */
public final class SimAgvTelemetryCollector implements RosbridgeHandler {

    private static final Logger log = LoggerFactory.getLogger(SimAgvTelemetryCollector.class);
    private static final String MAP_FRAME = "map";

    private final SimAgvProperties config;
    private final SimAgvTelemetry telemetry = new SimAgvTelemetry();
    private final String statusTopic;
    private final String tfTopic;
    private final String odomTopic;
    private final String scanTopic1;
    private final String scanTopic2;
    private final String poseTopic;
    private final long summaryIntervalMs;

    private volatile String lastLoggedState;
    private volatile String lastLoggedMode;
    private volatile long lastSummaryAtNanos = System.nanoTime();

    public SimAgvTelemetryCollector(SimAgvProperties config) {
        this.config = config;
        this.statusTopic = config.getStatusTopic();
        this.tfTopic = config.getTfTopic();
        this.odomTopic = config.getOdomTopic();
        this.scanTopic1 = config.getScanTopic1();
        this.scanTopic2 = config.getScanTopic2();
        this.poseTopic = config.getPoseTopic();
        this.summaryIntervalMs = Math.max(1000L, config.getSummaryIntervalMs());
    }

    public SimAgvTelemetry telemetry() {
        return telemetry;
    }

    @Override
    public void onConnected() {
        telemetry.setConnected(true);
        telemetry.touch();
        // 订阅动作由 RosbridgeClient 负责（connect 前注册，重连后自动恢复），这里只做记录
        log.info("Sim AGV rosbridge ready, waiting for {} / {} / {} / {}{}",
                statusTopic, tfTopic, scanTopic1, scanTopic2,
                config.isOdomEnabled() ? " / " + odomTopic : "");
    }

    @Override
    public void onDisconnected(int statusCode, String reason) {
        telemetry.setConnected(false);
        log.warn("Sim AGV rosbridge disconnected, will retry: code={}, reason={}", statusCode, reason);
    }

    @Override
    public void onMessage(JsonNode message) {
        if (!"publish".equals(message.path("op").asText())) {
            return;
        }
        String topic = message.path("topic").asText("");
        JsonNode payload = message.path("msg");
        if (payload.isMissingNode() || !payload.isObject()) {
            return;
        }
        telemetry.touch();
        if (statusTopic.equals(topic)) {
            handleStatus(payload);
        } else if (tfTopic.equals(topic)) {
            handleTf(payload);
        } else if (odomTopic.equals(topic)) {
            handleOdom(payload);
        } else if (scanTopic1.equals(topic)) {
            handleScan(payload, 1);
        } else if (scanTopic2.equals(topic)) {
            handleScan(payload, 2);
        } else if (poseTopic.equals(topic)) {
            handlePose(payload);
        }
        maybeLogSummary();
    }

    private void handleStatus(JsonNode payload) {
        String agvId = textOrNull(payload, "agv_id");
        String state = textOrNull(payload, "state");
        double battery = payload.path("battery").asDouble(Double.NaN);
        boolean poseInitialized = payload.path("pose_initialized").asBoolean(false);
        String activeCommandId = textOrNull(payload, "active_command_id");
        String activeNodeId = textOrNull(payload, "active_node_id");
        // v0.3.0+ 字段：业务模式与当前地图（旧版机器人不携带，保持 null）
        String mode = textOrNull(payload, "mode");
        String mapName = textOrNull(payload, "map_name");

        telemetry.setStatus(new SimAgvTelemetry.StatusSnapshot(
                agvId, state, battery, poseInitialized, activeCommandId, activeNodeId,
                mode, mapName, Instant.now()));

        String previous = lastLoggedState;
        if (state != null && !state.equals(previous)) {
            lastLoggedState = state;
            if (previous != null) {
                log.info("Sim AGV state change: {} -> {} (battery={}, localization={})",
                        previous, state, formatBattery(battery), poseInitialized);
            }
        }

        String previousMode = lastLoggedMode;
        if (mode != null && !mode.equals(previousMode)) {
            lastLoggedMode = mode;
            if (previousMode != null) {
                log.info("Sim AGV mode change: {} -> {} (map={})", previousMode, mode, mapName);
            }
        }
    }

    private void handleTf(JsonNode payload) {
        JsonNode transforms = payload.path("transforms");
        if (!transforms.isArray()) {
            return;
        }
        for (JsonNode transform : transforms) {
            String parentFrame = transform.path("header").path("frame_id").asText("");
            String childFrame = transform.path("child_frame_id").asText("");
            if (!MAP_FRAME.equals(parentFrame) || !config.getTfChildFrame().equals(childFrame)) {
                continue;
            }
            JsonNode translation = transform.path("transform").path("translation");
            JsonNode rotation = transform.path("transform").path("rotation");
            if (translation.isMissingNode() || rotation.isMissingNode()) {
                continue;
            }
            double x = translation.path("x").asDouble();
            double y = translation.path("y").asDouble();
            double yaw = yawFromQuaternion(
                    rotation.path("x").asDouble(),
                    rotation.path("y").asDouble(),
                    rotation.path("z").asDouble(),
                    rotation.path("w").asDouble());
            telemetry.setMapPose(new SimAgvTelemetry.PoseSnapshot(x, y, yaw, Instant.now()));
            return;
        }
    }

    private void handleOdom(JsonNode payload) {
        JsonNode position = payload.path("pose").path("pose").path("position");
        JsonNode orientation = payload.path("pose").path("pose").path("orientation");
        if (!position.isMissingNode() && !orientation.isMissingNode()) {
            double yaw = yawFromQuaternion(
                    orientation.path("x").asDouble(),
                    orientation.path("y").asDouble(),
                    orientation.path("z").asDouble(),
                    orientation.path("w").asDouble());
            telemetry.setOdomPose(new SimAgvTelemetry.PoseSnapshot(
                    position.path("x").asDouble(),
                    position.path("y").asDouble(),
                    yaw,
                    Instant.now()));
        }
        JsonNode twist = payload.path("twist").path("twist");
        if (!twist.isMissingNode()) {
            telemetry.setOdomVelocity(
                    twist.path("linear").path("x").asDouble(),
                    twist.path("angular").path("z").asDouble());
        }
    }

    /** /agv/pose：map 系 PoseStamped，10Hz 专用位姿，不受 /tf 混帧节流影响。 */
    private void handlePose(JsonNode payload) {
        JsonNode position = payload.path("pose").path("position");
        JsonNode orientation = payload.path("pose").path("orientation");
        if (position.isMissingNode() || orientation.isMissingNode()) {
            return;
        }
        double yaw = yawFromQuaternion(
                orientation.path("x").asDouble(),
                orientation.path("y").asDouble(),
                orientation.path("z").asDouble(),
                orientation.path("w").asDouble());
        telemetry.setMapPose(new SimAgvTelemetry.PoseSnapshot(
                position.path("x").asDouble(),
                position.path("y").asDouble(),
                yaw,
                Instant.now()));
    }

    private void handleScan(JsonNode payload, int which) {
        String frameId = payload.path("header").path("frame_id").asText("");
        JsonNode ranges = payload.path("ranges");
        if (!ranges.isArray()) {
            return;
        }
        double rangeMin = payload.path("range_min").asDouble(0.0);
        double rangeMax = payload.path("range_max").asDouble(Double.MAX_VALUE);
        int count = 0;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for (JsonNode r : ranges) {
            double v = r.asDouble(Double.NaN);
            if (Double.isNaN(v) || Double.isInfinite(v) || v <= rangeMin || v >= rangeMax) {
                continue;
            }
            count++;
            if (v < min) {
                min = v;
            }
            if (v > max) {
                max = v;
            }
        }
        SimAgvTelemetry.ScanSnapshot snapshot = new SimAgvTelemetry.ScanSnapshot(
                frameId, count, count == 0 ? 0.0 : min, count == 0 ? 0.0 : max, Instant.now());
        if (which == 1) {
            telemetry.setScan1(snapshot);
        } else {
            telemetry.setScan2(snapshot);
        }
    }

    /** 周期性 INFO 摘要：限流可见，证明每个话题都有数据进来。 */
    private void maybeLogSummary() {
        long now = System.nanoTime();
        if (now - lastSummaryAtNanos < summaryIntervalMs * 1_000_000L) {
            return;
        }
        lastSummaryAtNanos = now;

        var status = telemetry.getStatus();
        var pose = telemetry.getMapPose();
        var scan1 = telemetry.getScan1();
        var scan2 = telemetry.getScan2();

        String statusText = status == null ? "n/a"
                : String.format(Locale.ROOT, "%s/%.0f%%", status.state(), status.battery());
        String poseText = pose == null ? "n/a"
                : String.format(Locale.ROOT, "x=%.3f y=%.3f yaw=%.1fdeg", pose.x(), pose.y(), Math.toDegrees(pose.yaw()));
        String scan1Text = scan1 == null ? "n/a"
                : String.format(Locale.ROOT, "%d pts, range %.2f~%.2fm", scan1.rangeCount(), scan1.minRange(), scan1.maxRange());
        String scan2Text = scan2 == null ? "n/a"
                : String.format(Locale.ROOT, "%d pts, range %.2f~%.2fm", scan2.rangeCount(), scan2.minRange(), scan2.maxRange());

        log.info("Sim AGV summary | conn={} | status={} ({} msg) | pose {} ({} msg) | scan1 [{}] ({} msg) | scan2 [{}] ({} msg)",
                telemetry.isConnected(),
                statusText, telemetry.getStatusCount(),
                poseText, telemetry.getTfCount(),
                scan1Text, telemetry.getScan1Count(),
                scan2Text, telemetry.getScan2Count());
    }

    /** 平面运动 yaw = 2*atan2(z, w)（roll/pitch 近似为 0）。 */
    static double yawFromQuaternion(double x, double y, double z, double w) {
        double normSquared = x * x + y * y + z * z + w * w;
        if (normSquared < 1.0E-9) {
            return 0.0;
        }
        return 2.0 * Math.atan2(z / Math.sqrt(normSquared), w / Math.sqrt(normSquared));
    }

    private static String textOrNull(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String formatBattery(double battery) {
        return Double.isNaN(battery) ? "n/a" : String.format(Locale.ROOT, "%.1f", battery);
    }
}
