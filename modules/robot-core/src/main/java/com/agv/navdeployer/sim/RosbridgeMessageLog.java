package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ansi.AnsiOutput;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * rosbridge WebSocket 收发消息高亮日志（风格对齐 RMS zenoh 的 RmsMessageLog）。
 *
 * <p>打印策略：
 * <ul>
 *   <li>指令类（send_action_goal / call_service / advertise / subscribe / service_response /
 *       action_result）低频高价值——全量打印；</li>
 *   <li>话题数据按时间节流：同一话题/反馈每 topicIntervalMs 最多 1 条（/tf 实测可达 ~200Hz，
 *       计数采样不够稀；间隔配 0 或负值则完全不打话题数据，只留指令日志）；</li>
 *   <li>报文截断 maxChars（默认 300 字符）；/map 这类 MB 级消息只打长度摘要、不打内容。</li>
 * </ul>
 *
 * <p>颜色：发送青色、接收黄色（随 spring.output.ansi.enabled=always 生效，文件 appender 剥离颜色码）。
 * 频率与开关由 sim_agv.ws_message_log.* 配置，经 RosbridgeConfig 启动时注入。
 */
public final class RosbridgeMessageLog {

    private static final Logger log = LoggerFactory.getLogger("ROSBRIDGE-WS");

    private static final String RESET = "\u001B[0m";
    /** 发送（上位机→机器人）：亮青加粗 */
    private static final String OUT_COLOR = "\u001B[1;96m";
    /** 接收（机器人→上位机）：亮黄加粗 */
    private static final String IN_COLOR = "\u001B[1;93m";

    private static volatile boolean enabled = true;
    private static volatile int maxChars = 300;
    /** 话题数据时间节流间隔（毫秒）；<=0 表示不打话题数据（指令日志保留） */
    private static volatile long topicIntervalMs = 10_000;

    private static final ConcurrentHashMap<String, AtomicLong> lastLoggedAt = new ConcurrentHashMap<>();
    private static volatile Boolean ansiEnabled;

    private RosbridgeMessageLog() {
    }

    public static void configure(boolean enabledFlag, int maxCharLimit, long topicIntervalMillis) {
        enabled = enabledFlag;
        maxChars = Math.max(50, maxCharLimit);
        topicIntervalMs = topicIntervalMillis;
    }

    /** 上位机→机器人：发送的 rosbridge op（publish / call_service / send_action_goal / advertise…）。 */
    public static void outbound(JsonNode operation) {
        if (!enabled || operation == null) {
            return;
        }
        String op = operation.path("op").asText("");
        String topic = operation.path("topic").asText("");
        if ("publish".equals(op) && topic.contains("cmd_vel")) {
            if (!allowByTime("out:cmd_vel")) {
                return;
            }
        }
        String target = targetOf(operation, topic);
        log.info(out("<<<<< 【发送】上位机→机器人 | op={} | {} | payload={}", op, target, truncate(operation)));
    }

    /** 机器人→上位机：收到的 rosbridge op（publish / service_response / action_feedback / action_result…）。 */
    public static void inbound(JsonNode message, int rawLength) {
        if (!enabled || message == null) {
            return;
        }
        String op = message.path("op").asText("");
        switch (op) {
            case "publish" -> {
                String topic = message.path("topic").asText("");
                // /map 等大报文只打长度摘要（内容 MB 级，序列化打印纯属浪费）
                if (rawLength > 100_000) {
                    if (!allowByTime("in:big:" + topic)) {
                        return;
                    }
                    log.info(in(">>>>> 【接收】机器人→上位机 | op=publish | topic={} | <大报文 {} 字符，省略内容>",
                            topic, rawLength));
                    return;
                }
                if (!allowByTime("in:" + topic)) {
                    return;
                }
                log.info(in(">>>>> 【接收】机器人→上位机 | op=publish | topic={} | payload={}",
                        topic, truncate(message)));
            }
            case "action_feedback" -> {
                if (!allowByTime("in:feedback:" + message.path("id").asText(""))) {
                    return;
                }
                log.info(in(">>>>> 【接收】机器人→上位机 | op=action_feedback | id={} | payload={}",
                        message.path("id").asText(""), truncate(message)));
            }
            default -> log.info(in(">>>>> 【接收】机器人→上位机 | op={} | payload={}", op, truncate(message)));
        }
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

    /** 时间节流：同一 key 在 topicIntervalMs 内最多打 1 条（首条必打）；间隔 <=0 时话题数据直接不打。 */
    private static boolean allowByTime(String key) {
        long interval = topicIntervalMs;
        if (interval <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        AtomicLong last = lastLoggedAt.computeIfAbsent(key, k -> new AtomicLong(0));
        long prev = last.get();
        return now - prev >= interval && last.compareAndSet(prev, now);
    }

    private static String truncate(JsonNode node) {
        String text = node.toString();
        int limit = maxChars;
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

    /** SLF4J 风格 {} 占位符替换 */
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

    /** 惰性判定：仅 spring.output.ansi.enabled=always 时嵌入颜色码 */
    private static boolean ansiEnabled() {
        Boolean cached = ansiEnabled;
        if (cached == null) {
            cached = AnsiOutput.getEnabled() == AnsiOutput.Enabled.ALWAYS;
            ansiEnabled = cached;
        }
        return cached;
    }
}
