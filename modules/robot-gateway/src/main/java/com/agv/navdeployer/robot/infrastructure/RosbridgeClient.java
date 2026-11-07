package com.agv.navdeployer.robot.infrastructure;

import com.agv.navdeployer.robot.infrastructure.RosbridgeHandler;
import com.agv.navdeployer.robot.infrastructure.RosbridgeMessageAssembler;
import com.agv.navdeployer.robot.infrastructure.RosbridgeMessageLog;

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

    public void addHandler(RosbridgeHandler handler) {
        if (handler != null) {
            handlers.add(handler);
        }
    }

    public void connect() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        open();
    }

    public void subscribe(String topic, int throttleMs) {
        subscribe(topic, null, throttleMs, 0);
    }

    public void subscribe(String topic, int throttleMs, int fragmentSize) {
        subscribe(topic, null, throttleMs, fragmentSize);
    }

    public void subscribe(String topic, String type, int throttleMs, int fragmentSize) {
        subscribe(topic, type, throttleMs, fragmentSize, false);
    }

    public void subscribe(String topic, String type, int throttleMs, int fragmentSize, boolean transientLocal) {
        Subscription subscription = new Subscription(topic, type, throttleMs, fragmentSize, transientLocal);
        subscriptions.put(topic, subscription);
        if (isConnected()) {
            sendSubscribe(subscription);
        }
    }

    public void advertise(String topic, String type) {
        advertisements.put(topic, new Advertisement(topic, type));
        if (isConnected()) {
            sendAdvertise(advertisements.get(topic));
        }
    }

    public void unsubscribe(String topic) {
        subscriptions.remove(topic);
        InnerClient client = active.get();
        if (client != null && client.isOpen()) {
            ObjectNode operation = MAPPER.createObjectNode();
            operation.put("op", "unsubscribe");
            operation.put("topic", topic);
            send(operation);
        }
    }

    public void send(JsonNode operation) {
        InnerClient client = active.get();
        if (client == null || !client.isOpen()) {
            pendingOps.add(operation);
            log.debug("rosbridge not open, buffer operation: {}", operation);
            return;
        }
        RosbridgeMessageLog.outbound(operation);
        client.send(operation.toString());
    }

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
                RosbridgeMessageLog.inbound(message, raw.length());
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
        if (subscription.type() != null && !subscription.type().isBlank()) {
            operation.put("type", subscription.type());
        }
        if (subscription.throttleMs() > 0) {
            operation.put("throttle_rate", subscription.throttleMs());
        }
        operation.put("queue_length", 1);
        if (subscription.fragmentSize() > 0) {
            operation.put("fragment_size", subscription.fragmentSize());
        }
        if (subscription.transientLocal()) {
            ObjectNode qos = operation.putObject("qos");
            qos.put("durability", "transient_local");
            qos.put("reliability", "reliable");
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

    private record Subscription(String topic, String type, int throttleMs, int fragmentSize,
                                 boolean transientLocal) {
    }

    private record Advertisement(String topic, String type) {
    }
}
