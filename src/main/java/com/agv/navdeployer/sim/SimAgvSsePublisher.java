package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 *   es.addEventListener("heartbeat", ...);
 *
 * 事件：
 *   connected  —— 接入即回一条（含时间戳）
 *   telemetry  —— 每 telemetry-interval 一条聚合快照（状态/位姿/双雷达/计数）
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
