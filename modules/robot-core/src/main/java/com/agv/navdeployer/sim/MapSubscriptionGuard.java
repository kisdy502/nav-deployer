package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class MapSubscriptionGuard implements RosbridgeHandler {

    private static final Logger log = LoggerFactory.getLogger(MapSubscriptionGuard.class);
    private static final long LIVE_KEEP_MS = 45_000L;

    private final SimAgvProperties props;
    private volatile RosbridgeClient client;
    private volatile String mode;
    private volatile long lastLiveAccessMs;
    private volatile boolean subscribed;

    public MapSubscriptionGuard(SimAgvProperties props) {
        this.props = props;
    }

    public void attach(RosbridgeClient client) {
        this.client = client;
    }

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
