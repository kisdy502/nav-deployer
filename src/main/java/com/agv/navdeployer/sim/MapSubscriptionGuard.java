package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * /map 大消息按需订阅闸门。
 *
 * 背景：/map 是 2~3MB 的 JSON（rosbridge 单线程事件循环里序列化要数百 ms），
 * 常驻订阅会周期性阻塞同一条 websocket 上的所有小消息——/agv/status 被挤过
 * 新鲜度阈值、/cmd_vel 遥控指令迟钝，都是它挤的。而 /map 只在建图预览
 * （GET /api/v1/nav-maps/live 轮询）和快照建图时才真正需要。
 *
 * 策略（每秒 reconcile 一次）：
 * - 机器人 mode=MAPPING（/agv/status 实时解析）→ 订阅；
 * - /live 类接口被调用过且距上次不足 {@link #LIVE_KEEP_MS} → 订阅
 *   （非建图模式下手动开启实时预览也能看到图）；
 * - 其余情况退订，rosbridge 上的 /map 压力归零。
 *
 * 订阅带显式消息类型（props.mapTopicType）：连接早于 cartographer advertise 时
 * rosbridge 类型推断失败且永不重试，/map 会永久失明（实时预览只显示旧图的根因）。
 */
public class MapSubscriptionGuard implements RosbridgeHandler {

    private static final Logger log = LoggerFactory.getLogger(MapSubscriptionGuard.class);
    /** /live 轮询的保活窗口：前端 3s 一轮，窗口放大 15 倍容忍后端重启后的首询延迟。 */
    private static final long LIVE_KEEP_MS = 45_000L;

    private final SimAgvProperties props;
    private volatile RosbridgeClient client;
    /** 机器人业务模式（/agv/status.mode），旧版机器人可能为 null。 */
    private volatile String mode;
    private volatile long lastLiveAccessMs;
    private volatile boolean subscribed;

    public MapSubscriptionGuard(SimAgvProperties props) {
        this.props = props;
    }

    /** 由装配件回填 /map 专用通道（MapSubscriptionGuard 只在专用通道上订阅/退订）。 */
    public void attach(RosbridgeClient client) {
        this.client = client;
    }

    /** /live 类接口入口调用：45s 内维持订阅。 */
    public void touch() {
        lastLiveAccessMs = System.currentTimeMillis();
    }

    @Override
    public void onMessage(JsonNode message) {
        if (!"publish".equals(message.path("op").asText(""))) {
            return;
        }
        if (!props.getStatusTopic().equals(message.path("topic").asText(""))) {
            return;
        }
        JsonNode modeNode = message.path("msg").path("mode");
        String next = modeNode.isMissingNode() || modeNode.isNull() ? null : modeNode.asText();
        if (next != null && !next.equals(mode)) {
            log.info("map guard: robot mode {} -> {}", mode, next);
        }
        mode = next;
    }

    @Scheduled(fixedDelay = 1000)
    public void reconcile() {
        RosbridgeClient rosbridge = client;
        if (rosbridge == null || !props.isEnabled() || !props.isMapEnabled()) {
            return;
        }
        boolean want = "MAPPING".equals(mode)
                || System.currentTimeMillis() - lastLiveAccessMs < LIVE_KEEP_MS;
        if (want == subscribed) {
            return;
        }
        if (want) {
            rosbridge.subscribe(props.getMapTopic(), props.getMapTopicType(),
                    props.getMapThrottleMs(), props.getMapFragmentSize());
            subscribed = true;
            log.info("map subscription armed: topic={} (throttle={}ms, mode={}, live-access={}ms ago)",
                    props.getMapTopic(), props.getMapThrottleMs(), mode,
                    lastLiveAccessMs == 0 ? -1 : System.currentTimeMillis() - lastLiveAccessMs);
        } else {
            rosbridge.unsubscribe(props.getMapTopic());
            subscribed = false;
            log.info("map subscription released (mode={})", mode);
        }
    }
}
