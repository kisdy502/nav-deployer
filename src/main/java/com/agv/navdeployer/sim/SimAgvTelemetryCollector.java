package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 rosbridge 推送的仿真 AGV 话题解析成 {@link SimAgvTelemetry}：
 * - /agv/status：state / battery / pose_initialized / active_command_id / active_node_id
 * - /tf：过滤 header.frame_id=map 且 child_frame_id=<agv_id>/base_link 的变换，解算 x/y/yaw
 * - /tf_static：雷达安装变换（base_link → laser_frame 的平移+旋转），点云投影用
 * - /scan_1、/scan_2：2D 激光摘要 + 点云坐标（绑定捕获时位姿）
 * - /odom（可选）：位姿 + 线/角速度
 *
 * 日志策略：每条消息都更新 telemetry，另按 summary-interval-ms 周期输出一条
 * INFO 摘要，保证"限流但可见"。
 */
public final class SimAgvTelemetryCollector implements RosbridgeHandler {

    private static final Logger log = LoggerFactory.getLogger(SimAgvTelemetryCollector.class);
    private static final String MAP_FRAME = "map";

    /** 雷达安装变换：p_base = R(yaw)·p_laser + (dx,dy)。来自 /tf_static（标准来源），缺省回退 yaml 配置角。 */
    private record MountTransform(double dx, double dy, double yaw) {
    }

    private final SimAgvProperties config;
    private final SimAgvTelemetry telemetry = new SimAgvTelemetry();
    private final String statusTopic;
    private final String tfTopic;
    private final String tfStaticTopic;
    private final String odomTopic;
    private final String scanTopic1;
    private final String scanTopic2;
    /** 雷达安装朝向偏移（弧度）：雷达坐标系相对 base_link 的旋转，解析点云时叠加 */
    private final double scan1MountYaw;
    private final double scan2MountYaw;
    private final String poseTopic;
    private final long summaryIntervalMs;

    private volatile String lastLoggedState;
    private volatile String lastLoggedMode;
    private volatile long lastSummaryAtNanos = System.nanoTime();

    /** /tf_static 缓存：child frame（雷达 frame_id）→ base_link 系安装变换 */
    private final ConcurrentHashMap<String, MountTransform> mountByFrame = new ConcurrentHashMap<>();
    /** 已提示过"无 TF 安装变换、回退 yaml"的雷达（1/2），避免每帧刷屏 */
    private final java.util.Set<Integer> mountFallbackLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public SimAgvTelemetryCollector(SimAgvProperties config) {
        this.config = config;
        this.statusTopic = config.getStatusTopic();
        this.tfTopic = config.getTfTopic();
        this.tfStaticTopic = config.getTfStaticTopic();
        this.odomTopic = config.getOdomTopic();
        this.scanTopic1 = config.getScanTopic1();
        this.scanTopic2 = config.getScanTopic2();
        this.scan1MountYaw = Math.toRadians(config.getScan1MountYawDeg());
        this.scan2MountYaw = Math.toRadians(config.getScan2MountYawDeg());
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
        } else if (tfStaticTopic.equals(topic)) {
            handleTfStatic(payload);
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

    /**
     * /tf_static：静态安装关系。只关心 base_link → 雷达的变换（含平移+偏航），
     * 按 child frame（即 scan 的 header.frame_id）缓存，供点云还原安装变换。
     */
    private void handleTfStatic(JsonNode payload) {
        JsonNode transforms = payload.path("transforms");
        if (!transforms.isArray()) {
            return;
        }
        String baseLink = baseLinkSuffix();
        for (JsonNode transform : transforms) {
            String parentFrame = transform.path("header").path("frame_id").asText("");
            String childFrame = transform.path("child_frame_id").asText("");
            if (childFrame.isEmpty() || parentFrame.isEmpty()) {
                continue;
            }
            boolean parentIsBase = parentFrame.equals(baseLink) || parentFrame.endsWith("/" + baseLink);
            if (!parentIsBase) {
                continue;
            }
            JsonNode translation = transform.path("transform").path("translation");
            JsonNode rotation = transform.path("transform").path("rotation");
            if (translation.isMissingNode() || rotation.isMissingNode()) {
                continue;
            }
            MountTransform mount = new MountTransform(
                    translation.path("x").asDouble(),
                    translation.path("y").asDouble(),
                    yawFromQuaternion(
                            rotation.path("x").asDouble(),
                            rotation.path("y").asDouble(),
                            rotation.path("z").asDouble(),
                            rotation.path("w").asDouble()));
            if (mountByFrame.put(childFrame, mount) == null) {
                log.info("scan mount transform captured from {}: frame={}, parent={}, d=({}, {}), yaw={} deg",
                        tfStaticTopic, childFrame, parentFrame,
                        String.format(Locale.ROOT, "%.3f", mount.dx()),
                        String.format(Locale.ROOT, "%.3f", mount.dy()),
                        String.format(Locale.ROOT, "%.1f", Math.toDegrees(mount.yaw())));
            }
        }
    }

    /** tf_child_frame（如 AGV001/base_link）的末段，用于匹配 /tf_static 里 base_link 的各种写法 */
    private String baseLinkSuffix() {
        String tfChild = config.getTfChildFrame();
        int slash = tfChild.lastIndexOf('/');
        return slash >= 0 ? tfChild.substring(slash + 1) : tfChild;
    }

    /** 解析某雷达帧的安装变换：优先 /tf_static（精确→后缀匹配），否则回退 yaml 配置安装角（无平移）。 */
    private MountTransform resolveMount(String frameId, int which) {
        MountTransform mount = mountByFrame.get(frameId);
        if (mount == null) {
            for (var entry : mountByFrame.entrySet()) {
                String cached = entry.getKey();
                if (frameId.endsWith(cached) || cached.endsWith(frameId)) {
                    mount = entry.getValue();
                    break;
                }
            }
        }
        if (mount != null) {
            return mount;
        }
        if (mountFallbackLogged.add(which)) {
            log.warn("no /tf_static mount transform for scan frame '{}', falling back to config mount yaw ({} deg)",
                    frameId, which == 1 ? config.getScan1MountYawDeg() : config.getScan2MountYawDeg());
        }
        return new MountTransform(0.0, 0.0, which == 1 ? scan1MountYaw : scan2MountYaw);
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
        // 角度元数据：用于把极坐标 ranges 还原为 base_link 系直角坐标点云
        double angleMin = payload.path("angle_min").asDouble(Double.NaN);
        double angleInc = payload.path("angle_increment").asDouble(Double.NaN);
        boolean hasAngles = !Double.isNaN(angleMin) && !Double.isNaN(angleInc) && angleInc != 0.0;
        // 安装变换：优先 /tf_static（平移+旋转），回退 yaml 配置安装角。对装雷达不叠加就会画反
        MountTransform mount = resolveMount(frameId, which);

        int total = ranges.size();
        double[] points = hasAngles ? new double[total * 2] : new double[0];
        int pointCount = 0;
        int count = 0;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for (int i = 0; i < total; i++) {
            double v = ranges.get(i).asDouble(Double.NaN);
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
            if (hasAngles) {
                double angle = angleMin + i * angleInc + mount.yaw();
                points[pointCount++] = round3(v * Math.cos(angle) + mount.dx());
                points[pointCount++] = round3(v * Math.sin(angle) + mount.dy());
            }
        }
        if (pointCount < points.length) {
            points = java.util.Arrays.copyOf(points, pointCount);
        }
        // 点云是"这一时刻"的观测，必须绑定位姿才能正确投影到地图（机器人在动，用当前位姿画旧帧会错乱）
        SimAgvTelemetry.ScanSnapshot snapshot = new SimAgvTelemetry.ScanSnapshot(
                frameId, count, count == 0 ? 0.0 : min, count == 0 ? 0.0 : max, points,
                telemetry.getMapPose(), Instant.now());
        if (which == 1) {
            telemetry.setScan1(snapshot);
        } else {
            telemetry.setScan2(snapshot);
        }
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
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
                : String.format(Locale.ROOT, "%s, %d pts, range %.2f~%.2fm",
                        scan1.frameId(), scan1.rangeCount(), scan1.minRange(), scan1.maxRange());
        String scan2Text = scan2 == null ? "n/a"
                : String.format(Locale.ROOT, "%s, %d pts, range %.2f~%.2fm",
                        scan2.frameId(), scan2.rangeCount(), scan2.minRange(), scan2.maxRange());

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
