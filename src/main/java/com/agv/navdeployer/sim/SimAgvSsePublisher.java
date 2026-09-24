package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 仿真 AGV 遥测 SSE 推送器（语义对齐 taskflow 的 TaskflowSseEventPublisher）。
 *
 * 前端接入：
 *   const es = new EventSource("/sse/agv");
 *   es.addEventListener("telemetry", e => console.log(JSON.parse(e.data)));
 *   es.addEventListener("scan", e => console.log(JSON.parse(e.data)));
 *   es.addEventListener("heartbeat", ...);
 *
 * 事件：
 *   connected  —— 接入即回一条（含时间戳）
 *   telemetry  —— 每 telemetry-interval 一条聚合快照（状态/位姿/双雷达/计数）
 *   scan       —— 收到新雷达帧时推送点云（base_link 系扁平 [x0,y0,x1,y1,...]，约 2~3Hz）
 *   heartbeat  —— 每 30s 一条
 */
@Component
public class SimAgvSsePublisher {

    private static final Logger log = LoggerFactory.getLogger(SimAgvSsePublisher.class);
    private static final long SSE_TIMEOUT_MS = 0L; // 0 = 永不超时（对齐 taskflow 实现）
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CopyOnWriteArrayList<SseSubscription> subscriptions = new CopyOnWriteArrayList<>();
    private final SimAgvTelemetry telemetry;
    private final SimAgvProperties props;

    /** 上次推送过的 scan 快照引用（用于判断是否出现新帧，避免重复推送） */
    private volatile SimAgvTelemetry.ScanSnapshot lastPushedScan1;
    private volatile SimAgvTelemetry.ScanSnapshot lastPushedScan2;

    public SimAgvSsePublisher(SimAgvTelemetry telemetry, SimAgvProperties props) {
        this.telemetry = telemetry;
        this.props = props;
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        SseSubscription subscription = new SseSubscription(emitter);
        subscriptions.add(subscription);
        emitter.onCompletion(() -> subscriptions.remove(subscription));
        emitter.onTimeout(() -> subscriptions.remove(subscription));
        emitter.onError(error -> subscriptions.remove(subscription));
        send(subscription, "connected", Map.of("timestamp", Instant.now().toString()));
        log.info("SSE client connected (total={})", subscriptions.size());
        return emitter;
    }

    @Scheduled(fixedDelay = 1000)
    public void pushTelemetry() {
        if (subscriptions.isEmpty()) {
            return;
        }
        publish("telemetry", buildSnapshot());
    }

    /** 雷达点云推送：500ms 轮询，只有收到新帧（快照引用变化）才发，实际约等于雷达帧率（节流后 ~3Hz）。 */
    @Scheduled(fixedDelay = 500)
    public void pushScan() {
        if (subscriptions.isEmpty()) {
            return;
        }
        var scan1 = telemetry.getScan1();
        var scan2 = telemetry.getScan2();
        boolean scan1Changed = scan1 != null && scan1 != lastPushedScan1;
        boolean scan2Changed = scan2 != null && scan2 != lastPushedScan2;
        if (!scan1Changed && !scan2Changed) {
            return;
        }
        lastPushedScan1 = scan1;
        lastPushedScan2 = scan2;
        ObjectNode root = MAPPER.createObjectNode();
        root.put("timestamp", Instant.now().toString());
        appendScanCloud(root, "scan1", scan1);
        appendScanCloud(root, "scan2", scan2);
        publish("scan", root);
    }

    @Scheduled(fixedDelay = 30000)
    public void heartbeat() {
        publish("heartbeat", Map.of("timestamp", Instant.now().toString()));
    }

    public int clientCount() {
        return subscriptions.size();
    }

    /** 业务事件入口（如 move task 的 task 事件：创建 / 状态变更 / feedback 降频推送）。 */
    public void publishEvent(String eventName, Object payload) {
        if (subscriptions.isEmpty()) {
            return;
        }
        publish(eventName, payload);
    }

    /** 最新聚合快照（供 /agv/snapshot 轮询兜底）。 */
    public ObjectNode buildSnapshot() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("timestamp", Instant.now().toString());
        root.put("connected", telemetry.isConnected());

        var status = telemetry.getStatus();
        if (status != null) {
            ObjectNode node = root.putObject("status");
            node.put("agv_id", status.agvId());
            node.put("state", status.state());
            node.put("battery", status.battery());
            node.put("pose_initialized", status.poseInitialized());
            node.put("active_command_id", status.activeCommandId());
            node.put("active_node_id", status.activeNodeId());
            // v0.3.0+ 字段（旧版机器人为 null）：业务模式与当前地图
            node.put("mode", status.mode());
            node.put("map_name", status.mapName());
            node.put("received_at", status.receivedAt().toString());
            node.put("age_ms", telemetry.statusAgeMs());
        } else {
            root.putNull("status");
        }
        // 镜像状态新鲜度：模式类操作（切图/建图/保存）的业务闸门
        root.put("status_fresh", telemetry.isStatusFresh(props.getStatusFreshMs()));

        var pose = telemetry.getMapPose();
        if (pose != null) {
            ObjectNode node = root.putObject("pose");
            node.put("x", round(pose.x()));
            node.put("y", round(pose.y()));
            node.put("yaw", round(pose.yaw()));
            node.put("yaw_deg", round(Math.toDegrees(pose.yaw())));
        } else {
            root.putNull("pose");
        }

        appendScan(root, "scan1", telemetry.getScan1());
        appendScan(root, "scan2", telemetry.getScan2());

        root.put("msg_status", telemetry.getStatusCount());
        root.put("msg_pose", telemetry.getTfCount());
        root.put("msg_scan1", telemetry.getScan1Count());
        root.put("msg_scan2", telemetry.getScan2Count());
        return root;
    }

    private void appendScan(ObjectNode root, String field, SimAgvTelemetry.ScanSnapshot scan) {
        if (scan == null) {
            root.putNull(field);
            return;
        }
        ObjectNode node = root.putObject(field);
        node.put("frame_id", scan.frameId());
        node.put("range_count", scan.rangeCount());
        node.put("min_range", round(scan.minRange()));
        node.put("max_range", round(scan.maxRange()));
    }

    /** scan 事件用：摘要 + 点云扁平坐标数组 + 捕获时位姿（前端用它投影点云到地图） */
    private void appendScanCloud(ObjectNode root, String field, SimAgvTelemetry.ScanSnapshot scan) {
        if (scan == null) {
            root.putNull(field);
            return;
        }
        ObjectNode node = root.putObject(field);
        node.put("frame_id", scan.frameId());
        node.put("range_count", scan.rangeCount());
        var pose = scan.pose();
        if (pose != null) {
            ObjectNode poseNode = node.putObject("pose");
            poseNode.put("x", pose.x());
            poseNode.put("y", pose.y());
            poseNode.put("yaw", pose.yaw());
        } else {
            node.putNull("pose");
        }
        ArrayNode pts = node.putArray("points");
        double[] points = scan.points();
        if (points != null) {
            for (double v : points) {
                pts.add(v);
            }
        }
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private void publish(String eventName, Object payload) {
        for (SseSubscription subscription : new CopyOnWriteArrayList<>(subscriptions)) {
            send(subscription, eventName, payload);
        }
    }

    private void send(SseSubscription subscription, String eventName, Object payload) {
        try {
            subscription.emitter().send(SseEmitter.event().name(eventName).data(payload));
        } catch (Exception exception) {
            subscriptions.remove(subscription);
            try {
                subscription.emitter().complete();
            } catch (Exception ignored) {
                // 连接已断开，忽略关闭异常
            }
            log.debug("remove closed sim agv sse subscription, reason={}", exception.getMessage());
        }
    }

    private record SseSubscription(SseEmitter emitter) {
    }
}
