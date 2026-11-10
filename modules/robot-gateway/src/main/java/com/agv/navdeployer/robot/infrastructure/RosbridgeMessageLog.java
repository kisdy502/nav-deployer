package com.agv.navdeployer.robot.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ansi.AnsiOutput;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RosbridgeMessageLog {

    private static final Logger log = LoggerFactory.getLogger("ROSBRIDGE-WS");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESET = "\u001B[0m";
    private static final String OUT_COLOR = "\u001B[1;96m";
    private static final String IN_COLOR = "\u001B[1;93m";

    private static final Set<String> COMMAND_OUT_OPS = Set.of(
            "call_service", "send_action_goal", "cancel_action_goal", "advertise", "unsubscribe", "subscribe");

    private static volatile boolean commandsEnabled = true;
    private static volatile boolean topicEnabled = true;
    private static volatile int maxChars = 300;
    private static volatile int fullChars = 65_536;
    private static volatile long topicIntervalMs = 10_000;
    private static volatile long unchangedIntervalMs = 60_000;
    private static volatile long sensorIntervalMs = 30_000;
    private static volatile Set<String> stateTopics = Set.of();

    private static final ConcurrentHashMap<String, TopicState> topicStates = new ConcurrentHashMap<>();
    private static volatile Boolean ansiEnabled;

    private RosbridgeMessageLog() {
    }

    public static void configure(boolean commandsEnabledFlag, boolean topicEnabledFlag,
                                 int maxCharLimit, int fullCharLimit,
                                 long topicIntervalMillis, long unchangedIntervalMillis,
                                 long sensorIntervalMillis, Set<String> stateTopicNames) {
        commandsEnabled = commandsEnabledFlag;
        topicEnabled = topicEnabledFlag;
        maxChars = Math.max(50, maxCharLimit);
        fullChars = Math.max(Math.max(1000, fullCharLimit), maxChars);
        topicIntervalMs = topicIntervalMillis;
        unchangedIntervalMs = unchangedIntervalMillis;
        sensorIntervalMs = sensorIntervalMillis;
        stateTopics = stateTopicNames == null ? Set.of() : Set.copyOf(stateTopicNames);
    }

    public static void outbound(JsonNode operation) {
        if (operation == null) {
            return;
        }
        String op = operation.path("op").asText("");
        String topic = operation.path("topic").asText("");
        boolean commandOp = COMMAND_OUT_OPS.contains(op)
                || ("publish".equals(op) && !topic.contains("cmd_vel"));
        if (commandOp) {
            if (!commandsEnabled) {
                return;
            }
            log.info(out("<<<<< 【发送】上位机→机器人 | op={} | {} | payload={}",
                    op, targetOf(operation, topic), full(operation)));
            return;
        }
        if (!allowSensor("out:" + topic)) {
            return;
        }
        log.info(out("<<<<< 【发送|节流】上位机→机器人 | op={} | topic={} | payload={}",
                op, topic, truncate(operation)));
    }

    public static void inbound(JsonNode message, int rawLength) {
        if (message == null) {
            return;
        }
        String op = message.path("op").asText("");
        switch (op) {
            case "publish" -> {
                String topic = message.path("topic").asText("");
                if (rawLength > 8192) {
                    if (!allowSensor("in:big:" + topic)) {
                        return;
                    }
                    log.info(in(">>>>> 【接收|节流】机器人→上位机 | op=publish | topic={} | <大报文 {} 字符，省略内容>",
                            topic, rawLength));
                    return;
                }
                if (stateTopics.contains(topic)) {
                    TopicDecision decision = decideTopic("in:" + topic, message.path("msg"));
                    if (decision == null) {
                        return;
                    }
                    String payload = decision == TopicDecision.CHANGED ? full(message) : truncate(message);
                    log.info(in(">>>>> 【接收|{}】机器人→上位机 | op=publish | topic={} | payload={}",
                            decision.label(), topic, payload));
                } else {
                    if (!allowSensor("in:" + topic)) {
                        return;
                    }
                    log.info(in(">>>>> 【接收|节流】机器人→上位机 | op=publish | topic={} | payload={}",
                            topic, truncate(message)));
                }
            }
            case "action_feedback" -> {
                String id = message.path("id").asText("");
                if (!allowSensor("in:feedback:" + id)) {
                    return;
                }
                log.info(in(">>>>> 【接收|节流】机器人→上位机 | op=action_feedback | id={} | payload={}",
                        id, truncate(message)));
            }
            default -> {
                if (!commandsEnabled) {
                    return;
                }
                log.info(in(">>>>> 【接收】机器人→上位机 | op={} | payload={}", op, full(message)));
            }
        }
    }

    private enum TopicDecision {
        CHANGED("变化"), UNCHANGED("未变化保底");

        private final String label;

        TopicDecision(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private static final class TopicState {
        String lastContent;
        long lastLoggedMs;
    }

    private static boolean allowSensor(String key) {
        long interval = sensorIntervalMs;
        if (!topicEnabled || interval <= 0) {
            return false;
        }
        return allowByTime(key, interval);
    }

    private static TopicDecision decideTopic(String key, JsonNode payload) {
        if (!topicEnabled) {
            return null;
        }
        long changeGap = topicIntervalMs;
        if (changeGap <= 0) {
            return null;
        }
        String normalized = normalize(payload);
        TopicState state = topicStates.computeIfAbsent(key, k -> new TopicState());
        synchronized (state) {
            boolean unchanged = normalized.equals(state.lastContent);
            long gap = unchanged ? Math.max(changeGap, unchangedIntervalMs) : changeGap;
            long now = System.currentTimeMillis();
            if (now - state.lastLoggedMs < gap) {
                return null;
            }
            state.lastContent = normalized;
            state.lastLoggedMs = now;
            return unchanged ? TopicDecision.UNCHANGED : TopicDecision.CHANGED;
        }
    }

    private static boolean allowByTime(String key, long interval) {
        long now = System.currentTimeMillis();
        TopicState state = topicStates.computeIfAbsent(key, k -> new TopicState());
        synchronized (state) {
            if (now - state.lastLoggedMs < interval) {
                return false;
            }
            state.lastLoggedMs = now;
            return true;
        }
    }

    private static String normalize(JsonNode payload) {
        if (payload == null || payload.isMissingNode()) {
            return "";
        }
        return stripVolatile(payload).toString();
    }

    private static JsonNode stripVolatile(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = MAPPER.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                if (!"stamp".equals(entry.getKey())) {
                    copy.set(entry.getKey(), stripVolatile(entry.getValue()));
                }
            });
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = MAPPER.createArrayNode();
            for (JsonNode item : node) {
                copy.add(stripVolatile(item));
            }
            return copy;
        }
        if (node.isFloat() || node.isDouble()) {
            return new DoubleNode(Math.round(node.asDouble() * 100.0) / 100.0);
        }
        return node;
    }

    private static String targetOf(JsonNode operation, String topic) {
        if (!topic.isBlank()) {
            return "topic=" + topic;
        }
        String service = operation.path("service").asText("");
        if (!service.isBlank()) {
            return "service=" + service;
        }
        String action = operation.path("action").asText("");
        if (!action.isBlank()) {
            return "action=" + action;
        }
        return "-";
    }

    private static String truncate(JsonNode node) {
        String text = node.toString();
        int limit = maxChars;
        return text.length() <= limit ? text : text.substring(0, limit) + "…(共 " + text.length() + " 字符)";
    }

    private static String full(JsonNode node) {
        String text = node.toString();
        int limit = fullChars;
        return text.length() <= limit ? text : text.substring(0, limit) + "…(共 " + text.length() + " 字符)";
    }

    private static String out(String format, Object... args) {
        return color(OUT_COLOR, format, args);
    }

    private static String in(String format, Object... args) {
        return color(IN_COLOR, format, args);
    }

    private static String color(String colorCode, String format, Object... args) {
        String line = formatTemplate(format, args);
        return ansiEnabled() ? colorCode + line + RESET : line;
    }

    private static String formatTemplate(String template, Object... args) {
        if (args == null || args.length == 0) {
            return template;
        }
        StringBuilder builder = new StringBuilder(template.length() + 32);
        int argIndex = 0;
        int position = 0;
        while (argIndex < args.length) {
            int placeholder = template.indexOf("{}", position);
            if (placeholder < 0) {
                break;
            }
            builder.append(template, position, placeholder);
            builder.append(args[argIndex++]);
            position = placeholder + 2;
        }
        builder.append(template, position, template.length());
        return builder.toString();
    }

    private static boolean ansiEnabled() {
        Boolean cached = ansiEnabled;
        if (cached == null) {
            cached = AnsiOutput.getEnabled() == AnsiOutput.Enabled.ALWAYS;
            ansiEnabled = cached;
        }
        return cached;
    }
}

