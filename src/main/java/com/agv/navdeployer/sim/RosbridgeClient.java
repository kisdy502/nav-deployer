package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * rosbridge WebSocket 客户端，覆盖本项目用到的 op：
 * subscribe（含 fragment_size / throttle）/ advertise / publish 透传 / call_service / action。
 *
 * - 自动重连：断开后按配置延迟重连，重连成功后自动恢复全部订阅与 advertise；
 * - 分片重组：大消息（如 /map）按 rosbridge fragment 协议还原；
 * - 多 handler：消息按注册顺序分发给所有 handler（遥测采集、地图缓存、指令分发）；
 * - 回调运行在 WS IO 线程，handler 实现方自行保证快速返回。
 */
public final class RosbridgeClient {

    private static final Logger log = LoggerFactory.getLogger(RosbridgeClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String url;
    private final List<RosbridgeHandler> handlers = new CopyOnWriteArrayList<>();
    private final long reconnectDelayMs;
    private final RosbridgeMessageAssembler assembler = new RosbridgeMessageAssembler();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, Advertisement> advertisements = new ConcurrentHashMap<>();
    private final AtomicReference<InnerClient> active = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong subscriptionSequence = new AtomicLong();
    private final java.util.concurrent.ConcurrentLinkedQueue<JsonNode> pendingOps =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService reconnectScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "rosbridge-reconnect");
                thread.setDaemon(true);
                return thread;
            });

    public RosbridgeClient(String url, long reconnectDelayMs, RosbridgeHandler... handlers) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("rosbridge ws url must not be blank");
        }
        this.url = url;
        this.reconnectDelayMs = Math.max(500L, reconnectDelayMs);
        for (RosbridgeHandler handler : handlers) {
            if (handler != null) {
                this.handlers.add(handler);
            }
        }
    }

    /** 追加一个消息 handler（线程安全，可在连接建立后动态添加）。 */
    public void addHandler(RosbridgeHandler handler) {
        if (handler != null) {
            handlers.add(handler);
        }
    }

    /** 开始连接并启动自动重连。 */
    public void connect() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        open();
    }

    /** 订阅话题（重连后自动恢复）。throttleMs &lt;= 0 表示不限流。 */
    public void subscribe(String topic, int throttleMs) {
        subscribe(topic, throttleMs, 0);
    }

    /** 订阅话题，可指定 fragment_size（订阅 /map 这类大消息时必填，例如 500000）。 */
    public void subscribe(String topic, int throttleMs, int fragmentSize) {
        Subscription subscription = new Subscription(topic, throttleMs, fragmentSize);
        subscriptions.put(topic, subscription);
        if (isConnected()) {
            sendSubscribe(subscription);
        }
    }

    /** 声明发布话题（重连后自动恢复），之后方可 publish 该话题。 */
    public void advertise(String topic, String type) {
        advertisements.put(topic, new Advertisement(topic, type));
        if (isConnected()) {
            sendAdvertise(advertisements.get(topic));
        }
    }

    /** 发送一条任意 rosbridge op 消息（publish / call_service / send_action_goal 等）。未连接时先缓冲。 */
    public void send(JsonNode operation) {
        InnerClient client = active.get();
        if (client == null || !client.isOpen()) {
            pendingOps.add(operation);
            log.debug("rosbridge not open, buffer operation: {}", operation);
            return;
        }
        client.send(operation.toString());
    }

    /** 停止重连并关闭连接。 */
    public void close() {
        running.set(false);
        reconnectScheduler.shutdownNow();
        InnerClient client = active.getAndSet(null);
        if (client != null) {
            try {
                client.closeBlocking();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isConnected() {
        InnerClient client = active.get();
        return client != null && client.isOpen();
    }

    private void open() {
        try {
            InnerClient client = new InnerClient(URI.create(url));
            active.set(client);
            client.connectBlocking();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running.set(false);
        } catch (RuntimeException exception) {
            log.warn("rosbridge connect failed: {}", exception.getMessage());
            scheduleReconnect();
        }
    }

    private void onInnerOpen() {
        log.info("rosbridge connected: {}", url);
        // 重连后按原始配置恢复所有 advertise 与订阅
        new ArrayList<>(advertisements.values()).forEach(this::sendAdvertise);
        new ArrayList<>(subscriptions.values()).forEach(this::sendSubscribe);
        flushPendingOps();
        handlers.forEach(RosbridgeHandler::onConnected);
    }

    private void onInnerClose(int statusCode, String reason) {
        log.info("rosbridge disconnected: code={}, reason={}", statusCode, reason);
        handlers.forEach(handler -> handler.onDisconnected(statusCode, reason == null ? "" : reason));
        scheduleReconnect();
    }

    private void flushPendingOps() {
        JsonNode buffered;
        while ((buffered = pendingOps.poll()) != null) {
            send(buffered);
        }
    }

    private void onInnerMessage(String raw) {
        try {
            JsonNode message = assembler.add(raw);
            if (message != null) {
                for (RosbridgeHandler handler : handlers) {
                    try {
                        handler.onMessage(message);
                    } catch (Exception handlerException) {
                        log.warn("handler {} failed on message: {}", handler.getClass().getSimpleName(),
                                handlerException.getMessage());
                    }
                }
            }
        } catch (Exception exception) {
            log.warn("failed to process rosbridge message: {}", exception.getMessage());
            handlers.forEach(handler -> handler.onError(exception));
        }
    }

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }
        reconnectScheduler.schedule(this::open, reconnectDelayMs, TimeUnit.MILLISECONDS);
    }

    private void sendSubscribe(Subscription subscription) {
        ObjectNode operation = MAPPER.createObjectNode();
        operation.put("op", "subscribe");
        operation.put("id", "sub-" + subscription.topic() + "-" + subscriptionSequence.incrementAndGet());
        operation.put("topic", subscription.topic());
        if (subscription.throttleMs() > 0) {
            operation.put("throttle_rate", subscription.throttleMs());
        }
        operation.put("queue_length", 1);
        if (subscription.fragmentSize() > 0) {
            operation.put("fragment_size", subscription.fragmentSize());
        }
        send(operation);
    }

    private void sendAdvertise(Advertisement advertisement) {
        ObjectNode operation = MAPPER.createObjectNode();
        operation.put("op", "advertise");
        operation.put("id", "adv-" + advertisement.topic());
        operation.put("topic", advertisement.topic());
        operation.put("type", advertisement.type());
        send(operation);
    }

    private final class InnerClient extends WebSocketClient {

        private InnerClient(URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            RosbridgeClient.this.onInnerOpen();
        }

        @Override
        public void onMessage(String message) {
            RosbridgeClient.this.onInnerMessage(message);
        }

        @Override
        public void onMessage(ByteBuffer bytes) {
            // rosbridge JSON 模式下不会出现二进制帧（CBOR 模式未启用）
            log.debug("ignore binary frame, {} bytes", bytes.remaining());
        }

        @Override
        public void onClose(int statusCode, String reason, boolean remote) {
            RosbridgeClient.this.onInnerClose(statusCode, reason);
        }

        @Override
        public void onError(Exception exception) {
            if (exception != null) {
                log.warn("rosbridge transport error: {}", exception.getMessage());
                handlers.forEach(handler -> handler.onError(exception));
            }
        }
    }

    private record Subscription(String topic, int throttleMs, int fragmentSize) {
    }

    private record Advertisement(String topic, String type) {
    }
}
