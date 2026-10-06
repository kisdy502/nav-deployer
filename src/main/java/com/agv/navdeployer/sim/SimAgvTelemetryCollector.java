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
    private final String poseTopic;
    private final long summaryIntervalMs;

    private volatile String lastLoggedState;
    private volatile String lastLoggedMode;
    private volatile long lastSummaryAtNanos = System.nanoTime();

    /** 位姿历史环（/tf 与 /agv/pose 按各自 ROS 时间戳入环，雷达帧按扫描时刻插值取用）。 */
    private final java.util.ArrayDeque<SimAgvTelemetry.PoseSnapshot> poseHistory = new java.util.ArrayDeque<>();
    private static final int POSE_HISTORY_MAX = 96;
    /** 两段式位姿环：map→odom（cartographer 段）与 odom→base_link（底盘段），各自独立插值再复合 */
    private final java.util.ArrayDeque<SimAgvTelemetry.PoseSnapshot> mapOdomHistory = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<SimAgvTelemetry.PoseSnapshot> odomBaseHistory = new java.util.ArrayDeque<>();
    /** 时基回退阈值：超过视为仿真重启（sim time 归零），位姿环清空重建 */
    private static final double MAX_TIME_BASE_RESET_SEC = 5.0;

    /** /tf_static 缓存：child frame（雷达 frame_id）→ base_link 系安装变换 */
    private final ConcurrentHashMap<String, MountTransform> mountByFrame = new ConcurrentHashMap<>();
    /**
     * 安装变换缺失告警状态（frame → [首次缺失时刻, 上次告警时刻]）。
     * 启动竞态宽限：/tf_static 的 latched 消息常晚于首批扫描几十毫秒到达，
     * 宽限期内 INFO 提示一次并静默等待；超时仍缺才升 ERROR（真故障）。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, long[]> mountMissingLastLog =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long MOUNT_GRACE_MS = 15_000L;

    public SimAgvTelemetryCollector(SimAgvProperties config) {
        this.config = config;
        this.statusTopic = config.getStatusTopic();
        this.tfTopic = config.getTfTopic();
        this.tfStaticTopic = config.getTfStaticTopic();
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
            JsonNode translation = transform.path("transform").path("translation");
            JsonNode rotation = transform.path("transform").path("rotation");
            if (translation.isMissingNode() || rotation.isMissingNode()) {
                continue;
            }
            double stamp = stampSec(transform.path("header"));
            if (stamp <= 0.0) {
                continue;
            }
            double x = translation.path("x").asDouble();
            double y = translation.path("y").asDouble();
            double yaw = yawFromQuaternion(
                    rotation.path("x").asDouble(),
                    rotation.path("y").asDouble(),
                    rotation.path("z").asDouble(),
                    rotation.path("w").asDouble());
            SimAgvTelemetry.PoseSnapshot sample =
                    new SimAgvTelemetry.PoseSnapshot(x, y, yaw, stamp, Instant.now());

            // 【两段式（RViz 同款）】map→odom（cartographer 修正跳变段）与
            // odom→base_link（底盘高频平滑段）各自入环，扫描时刻分段插值后复合。
            // 在复合总位姿上做插值会跨修正跳变取"不存在的中间角"——旋转夹角的根因。
            if (MAP_FRAME.equals(parentFrame) && "odom".equals(childFrame)) {
                appendSample(mapOdomHistory, sample);
                flushPendingScans();
            } else if ("odom".equals(parentFrame) && "base_link".equals(childFrame)) {
                appendSample(odomBaseHistory, sample);
                flushPendingScans();
            } else if (MAP_FRAME.equals(parentFrame) && config.getTfChildFrame().equals(childFrame)) {
                // bridge 广播的复合帧（10Hz）：作为两段式不可用时的回退位姿源
                telemetry.setMapPose(sample);
                recordPose(sample);
            }
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
            mountMissingLastLog.remove(childFrame);
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

    /**
     * 解析某雷达帧的安装变换：唯一来源 /tf_static（精确→后缀匹配）。
     * 缺失 = 机器人自描述不完整：返回 null，调用方丢帧并打 ERROR——
     * 绝不用猜测值兼容，错误的安装角会让点云整体旋转错位。
     */
    private MountTransform resolveMount(String frameId) {
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
        return mount;
    }

    /** 安装变换缺失告警：15s 启动宽限（INFO 一次），之后每 10s 一条 ERROR。 */
    private void logMountMissing(String frameId) {
        long now = System.currentTimeMillis();
        long[] state = mountMissingLastLog.computeIfAbsent(frameId, key -> new long[]{now, 0L});
        synchronized (state) {
            if (now - state[0] < MOUNT_GRACE_MS) {
                if (state[1] == 0L) {
                    state[1] = now;
                    log.info("等待雷达安装变换（/tf_static latched 消息尚未送达）：frame='{}'，"
                            + "该雷达点云暂缓发布，宽限期内送达即自动恢复", frameId);
                }
                return;
            }
            if (now - state[1] < 10_000L) {
                return;
            }
            state[1] = now;
        }
        log.error("缺少雷达安装位置：scan frame '{}' 在 /tf_static 中没有 base_link 父级的静态安装变换，"
                + "该雷达点云已被丢弃。请检查机器人侧 robot_state_publisher/URDF 是否发布该 frame，"
                + "以及 bridge 的 /tf_static 通道是否可达（transient_local 订阅）", frameId);
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
                    stampSec(payload.path("header")),
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
        SimAgvTelemetry.PoseSnapshot pose = new SimAgvTelemetry.PoseSnapshot(
                position.path("x").asDouble(),
                position.path("y").asDouble(),
                yaw,
                stampSec(payload.path("header")),
                Instant.now());
        telemetry.setMapPose(pose);
        recordPose(pose);
    }

    // ==================== 扫描时刻位姿回溯（雷达错位修复的核心） ====================

    /**
     * 位姿入环（轻微乱序丢弃，容量溢出裁剪最老样本）。
     * 时基回退检测：新样本时间戳比环内最新样本倒退超过 {@link #MAX_TIME_BASE_RESET_SEC}
     * 时判定为【仿真重启】（sim time 归零重新计数）——清空整环重建。不清环的话新样本
     * 会永远被判乱序丢弃，雷达帧绑到旧会话位姿上，偏移可达数米到十几米
     * （机器人两次会话的位置差）；/tf 节流造成的毫秒级乱序不会触发该阈值。
     */
    private void recordPose(SimAgvTelemetry.PoseSnapshot pose) {
        if (pose.stampSec() <= 0.0) {
            return;
        }
        boolean appended;
        synchronized (poseHistory) {
            appended = appendSampleLocked(poseHistory, pose);
        }
        if (appended) {
            flushPendingScans();
        }
    }

    /** 环内追加样本（时基回退清环重建、轻微乱序丢弃、容量裁剪）。 */
    private void appendSample(java.util.ArrayDeque<SimAgvTelemetry.PoseSnapshot> ring,
                              SimAgvTelemetry.PoseSnapshot sample) {
        synchronized (ring) {
            appendSampleLocked(ring, sample);
        }
    }

    private boolean appendSampleLocked(java.util.ArrayDeque<SimAgvTelemetry.PoseSnapshot> ring,
                                       SimAgvTelemetry.PoseSnapshot sample) {
        if (!ring.isEmpty()) {
            double last = ring.getLast().stampSec();
            if (sample.stampSec() < last - MAX_TIME_BASE_RESET_SEC) {
                ring.clear();
                log.info("pose history reset: time base jumped backward {}s -> {}s "
                        + "(simulation restarted?), rebuilding ring", last, sample.stampSec());
            } else if (sample.stampSec() <= last) {
                return false;
            }
        }
        ring.addLast(sample);
        while (ring.size() > POSE_HISTORY_MAX) {
            ring.pollFirst();
        }
        return true;
    }

    // ==================== 扫描时刻位姿绑定（RViz message-filter 同语义） ====================
    // RViz 显示雷达永远贴合障碍物的原理：扫描帧挂起，等 TF 覆盖到扫描时刻后按该时刻
    // 精确插值变换。这里照抄该语义：扫描先入挂起队列，位姿环每前进一格就冲洗一次，
    // 已覆盖时刻的扫描用环内插值绑定发布——绝不外推（转弯起始瞬间外推速度滞后，
    // 会让点云短暂旋转错位；位姿断流时挂起帧按墙钟超时丢弃）。

    /** 每雷达挂起扫描的容量与超时（位姿 10Hz → 正常等待 ≤100ms，500ms 还没覆盖就是异常） */
    private static final int PENDING_SCANS_MAX = 4;
    private static final long PENDING_SCAN_TIMEOUT_NANOS = 500_000_000L;
    private final java.util.ArrayDeque<PendingScan> pendingScans1 = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<PendingScan> pendingScans2 = new java.util.ArrayDeque<>();

    private record PendingScan(double stampSec, String frameId, int count, double min, double max,
                               double[] points, long enqueuedAtNanos) {
    }

    /** 扫描时刻已被位姿源覆盖（可精确插值）：优先两段式，回退复合环。 */
    private boolean poseRingCovers(double stampSec) {
        return hopPoseAvailable(stampSec) || composedRingCovers(stampSec);
    }

    private boolean composedRingCovers(double stampSec) {
        synchronized (poseHistory) {
            return !poseHistory.isEmpty() && poseHistory.getLast().stampSec() >= stampSec;
        }
    }

    /** 两段式可用：两环都非空且最新样本都不早于扫描时刻。 */
    private boolean hopPoseAvailable(double stampSec) {
        synchronized (mapOdomHistory) {
            if (mapOdomHistory.isEmpty() || mapOdomHistory.getLast().stampSec() < stampSec) {
                return false;
            }
        }
        synchronized (odomBaseHistory) {
            return !odomBaseHistory.isEmpty() && odomBaseHistory.getLast().stampSec() >= stampSec;
        }
    }

    /**
     * 两段式取扫描时刻位姿（RViz 复合数学）：map→odom 与 odom→base_link 各自按时刻
     * 插值，再复合成 map→base_link。跳变只发生在 map→odom 段，分段插值不会跨跳变
     * 取中间角；调用方保证 hopPoseAvailable。
     */
    private SimAgvTelemetry.PoseSnapshot hopPoseAt(double stampSec) {
        SimAgvTelemetry.PoseSnapshot mo;
        SimAgvTelemetry.PoseSnapshot ob;
        synchronized (mapOdomHistory) {
            mo = interpRing(mapOdomHistory, stampSec);
        }
        synchronized (odomBaseHistory) {
            ob = interpRing(odomBaseHistory, stampSec);
        }
        double yaw = mo.yaw() + ob.yaw();
        double cos = Math.cos(mo.yaw());
        double sin = Math.sin(mo.yaw());
        return new SimAgvTelemetry.PoseSnapshot(
                mo.x() + cos * ob.x() - sin * ob.y(),
                mo.y() + sin * ob.x() + cos * ob.y(),
                yaw,
                stampSec,
                mo.receivedAt());
    }

    private void enqueuePending(int which, PendingScan scan) {
        java.util.ArrayDeque<PendingScan> queue = which == 1 ? pendingScans1 : pendingScans2;
        queue.addLast(scan);
        while (queue.size() > PENDING_SCANS_MAX) {
            queue.pollFirst();
        }
    }

    /** 位姿环前进后调用：把已覆盖时刻的挂起扫描插值绑定并发布，超时的丢弃。 */
    private void flushPendingScans() {
        // 覆盖进度取两段式与复合环二者的最新时刻（谁新用谁）
        double newest = 0.0;
        synchronized (mapOdomHistory) {
            if (!mapOdomHistory.isEmpty()) {
                newest = Math.max(newest, mapOdomHistory.getLast().stampSec());
            }
        }
        synchronized (odomBaseHistory) {
            if (!odomBaseHistory.isEmpty()) {
                newest = Math.max(newest, odomBaseHistory.getLast().stampSec());
            }
        }
        synchronized (poseHistory) {
            if (!poseHistory.isEmpty()) {
                newest = Math.max(newest, poseHistory.getLast().stampSec());
            }
        }
        if (newest <= 0.0) {
            return;
        }
        flushPendingQueue(pendingScans1, newest, 1);
        flushPendingQueue(pendingScans2, newest, 2);
    }

    /** 扫描绑定位姿：两段式可用用两段式（RViz 复合数学），否则回退复合环。 */
    private SimAgvTelemetry.PoseSnapshot bindPoseForScan(double stampSec) {
        if (hopPoseAvailable(stampSec)) {
            return hopPoseAt(stampSec);
        }
        return poseAt(stampSec);
    }

    private void flushPendingQueue(java.util.ArrayDeque<PendingScan> queue, double newestStamp, int which) {
        while (!queue.isEmpty()) {
            PendingScan scan = queue.peekFirst();
            if (newestStamp >= scan.stampSec()) {
                queue.pollFirst();
                publishScan(which, scan, bindPoseForScan(scan.stampSec()));
            } else if (System.nanoTime() - scan.enqueuedAtNanos() > PENDING_SCAN_TIMEOUT_NANOS) {
                // 位姿流断流/时钟异常：丢弃，不留错误数据
                queue.pollFirst();
                log.debug("drop stale pending scan frame={} stamp={}", scan.frameId(), scan.stampSec());
            } else {
                break;
            }
        }
    }

    /** 发布一帧扫描（pose 为绑定位姿）。 */
    private void publishScan(int which, PendingScan scan, SimAgvTelemetry.PoseSnapshot pose) {
        SimAgvTelemetry.ScanSnapshot snapshot = new SimAgvTelemetry.ScanSnapshot(
                scan.frameId(), scan.count(),
                scan.count() == 0 ? 0.0 : scan.min(),
                scan.count() == 0 ? 0.0 : scan.max(),
                scan.points(), pose, Instant.now());
        if (which == 1) {
            telemetry.setScan1(snapshot);
        } else {
            telemetry.setScan2(snapshot);
        }
    }

    /**
     * 取指定 ROS 时刻的 map 系位姿：环内两样本线性插值（yaw 走最短角差）。
     * 调用方保证 stamp 已被环覆盖（poseRingCovers）；边界钳位到最近样本仅作兜底。
     */
    private SimAgvTelemetry.PoseSnapshot poseAt(double stampSec) {
        SimAgvTelemetry.PoseSnapshot latest = telemetry.getMapPose();
        synchronized (poseHistory) {
            return interpRing(poseHistory, stampSec, latest);
        }
    }

    /** 环内按时刻线性插值（yaw 最短角差）；环空返回 fallback，边界钳位最近样本。 */
    private static SimAgvTelemetry.PoseSnapshot interpRing(
            java.util.ArrayDeque<SimAgvTelemetry.PoseSnapshot> ring, double stampSec,
            SimAgvTelemetry.PoseSnapshot fallback) {
        if (ring.isEmpty()) {
            return fallback;
        }
        if (stampSec <= ring.getFirst().stampSec()) {
            return ring.getFirst();
        }
        if (stampSec >= ring.getLast().stampSec()) {
            return ring.getLast();
        }
        SimAgvTelemetry.PoseSnapshot prev = null;
        for (SimAgvTelemetry.PoseSnapshot cur : ring) {
            if (cur.stampSec() >= stampSec) {
                if (prev == null) {
                    return cur;
                }
                double span = cur.stampSec() - prev.stampSec();
                double t = span <= 0.0 ? 0.0 : (stampSec - prev.stampSec()) / span;
                double dyaw = cur.yaw() - prev.yaw();
                dyaw = Math.atan2(Math.sin(dyaw), Math.cos(dyaw));
                return new SimAgvTelemetry.PoseSnapshot(
                        prev.x() + (cur.x() - prev.x()) * t,
                        prev.y() + (cur.y() - prev.y()) * t,
                        prev.yaw() + dyaw * t,
                        stampSec,
                        prev.receivedAt());
            }
            prev = cur;
        }
        return fallback;
    }

    private static SimAgvTelemetry.PoseSnapshot interpRing(
            java.util.ArrayDeque<SimAgvTelemetry.PoseSnapshot> ring, double stampSec) {
        return interpRing(ring, stampSec, ring.getLast());
    }

    /** ROS header 时间戳 → 秒（缺 stamp 返回 0，调用方按"无时间戳"回退）。 */
    private static double stampSec(JsonNode header) {
        if (header.isMissingNode()) {
            return 0.0;
        }
        JsonNode stamp = header.path("stamp");
        return stamp.path("sec").asDouble(0.0) + stamp.path("nanosec").asDouble(0.0) / 1e9;
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
        // 安装变换：唯一来源 /tf_static；缺失则丢帧并 ERROR（不做错误兼容）
        MountTransform mount = resolveMount(frameId);
        if (mount == null) {
            logMountMissing(frameId);
            return;
        }

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
        // RViz 语义：扫描时刻未被位姿环覆盖时挂起，等位姿样本到位后精确插值绑定
        //（见 flushPendingScans）；消息缺时间戳时立即用最新位姿发布（兜底）
        double scanStamp = stampSec(payload.path("header"));
        PendingScan scan = new PendingScan(scanStamp, frameId, count, min, max, points,
                System.nanoTime());
        if (scanStamp > 0.0 && !poseRingCovers(scanStamp)) {
            enqueuePending(which, scan);
            return;
        }
        publishScan(which, scan, scanStamp > 0.0 ? bindPoseForScan(scanStamp) : telemetry.getMapPose());
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
