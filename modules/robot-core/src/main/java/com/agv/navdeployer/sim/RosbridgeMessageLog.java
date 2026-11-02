package com.agv.navdeployer.sim;

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

/**
 * rosbridge WebSocket 收发消息高亮日志（风格对齐 RMS zenoh 的 RmsMessageLog）。
 *
 * <p>打印策略（按消息价值分级）：
 * <ul>
 *   <li><b>指令类（一次性，绝不漏打）</b>——发送 call_service / send_action_goal /
 *       cancel_action_goal / advertise、接收 service_response / action_result 等终态回包：
 *       每条必打且<b>全量内容</b>（上限 fullChars，默认 64KB），方便复制完整 JSON 做格式化分析。
 *       由 commandsEnabled 控制（默认开）。</li>
 *   <li><b>业务状态话题</b>（/agv/status、/agv/localization_operation，configure 注入名单）：
 *       <ul>
 *         <li><b>内容变化</b>（规范化比较：去 stamp、浮点取 2 位）→ 每 topicIntervalMs 最多 1 条，
 *             打<b>全量内容</b>——state/mode/charging 迁移给程序员完整报文分析；</li>
 *         <li><b>内容未变化</b> → 每 unchangedIntervalMs 保底 1 条（默认 60s），只打 maxChars 摘要。</li>
 *       </ul></li>
 *   <li><b>传感器流话题</b>（雷达 /agv/pose /tf /odom /joint_states、action_feedback、cmd_vel——
 *       名单外的一切 publish 话题）：纯数据流无变化分析价值，固定每 sensorIntervalMs
 *       （默认 30s）1 条摘要，不做变化检测。</li>
 * </ul>
 *
 * <p>/map 这类 &gt;100KB 的大报文只打长度摘要，不打内容。
 * <p>颜色：发送青色、接收黄色（随 spring.output.ansi.enabled=always 生效，文件 appender 剥离颜色码）。
 * 频率与开关由 sim_agv.ws_message_log.* 配置，经 RosbridgeConfig 启动时注入。
 */
public final class RosbridgeMessageLog {

    private static final Logger log = LoggerFactory.getLogger("ROSBRIDGE-WS");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESET = "\u001B[0m";
    /** 发送（上位机→机器人）：亮青加粗 */
    private static final String OUT_COLOR = "\u001B[1;96m";
    /** 接收（机器人→上位机）：亮黄加粗 */
    private static final String IN_COLOR = "\u001B[1;93m";

    /** 指令类 op（发送方向）：一次性高价值，全量打印。 */
    private static final Set<String> COMMAND_OUT_OPS = Set.of(
            "call_service", "send_action_goal", "cancel_action_goal", "advertise", "unsubscribe", "subscribe");

    private static volatile boolean commandsEnabled = true;
    private static volatile boolean topicEnabled = true;
    /** 未变化保底/传感器流摘要的截断长度（字符） */
    private static volatile int maxChars = 300;
    /** 变化/指令类「全量打印」的截断上限（字符）：防 MB 级报文（/map 响应）打爆日志 */
    private static volatile int fullChars = 65_536;
    /** 业务状态话题「内容变化」的打印间隔（毫秒）；<=0 表示不打话题数据（指令日志保留） */
    private static volatile long topicIntervalMs = 10_000;
    /** 业务状态话题「内容未变化」的保底打印间隔（毫秒） */
    private static volatile long unchangedIntervalMs = 60_000;
    /** 传感器流话题的固定打印间隔（毫秒），不做变化检测 */
    private static volatile long sensorIntervalMs = 30_000;
    /** 业务状态话题名单（configure 注入：/agv/status、/agv/localization_operation 等） */
    private static volatile Set<String> stateTopics = Set.of();

    /** 各话题的日志状态（规范化内容 + 上次打印时间），内容感知节流用。 */
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

    /** 上位机→机器人：发送的 rosbridge op。 */
    public static void outbound(JsonNode operation) {
        if (operation == null) {
            return;
        }
        String op = operation.path("op").asText("");
        String topic = operation.path("topic").asText("");
        boolean commandOp = COMMAND_OUT_OPS.contains(op)
                // publish 中的 goal_pose 等一次性导航指令按指令类全量；cmd_vel 遥控流按传感器流节流
                || ("publish".equals(op) && !topic.contains("cmd_vel"));
        if (commandOp) {
            if (!commandsEnabled) {
                return;
            }
            // 一次性指令：全量内容（fullChars 上限），完整可对账
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

    /** 机器人→上位机：收到的 rosbridge op。 */
    public static void inbound(JsonNode message, int rawLength) {
        if (message == null) {
            return;
        }
        String op = message.path("op").asText("");
        switch (op) {
            case "publish" -> {
                String topic = message.path("topic").asText("");
                // /map 等大报文只打长度摘要（内容 MB 级，序列化打印纯属浪费）
                if (rawLength > 100_000) {
                    if (!allowSensor("in:big:" + topic)) {
                        return;
                    }
                    log.info(in(">>>>> 【接收|节流】机器人→上位机 | op=publish | topic={} | <大报文 {} 字符，省略内容>",
                            topic, rawLength));
                    return;
                }
                if (stateTopics.contains(topic)) {
                    // 业务状态话题：变化 → 全量（完整 JSON 供分析）；未变化 → 保底摘要
                    TopicDecision decision = decideTopic("in:" + topic, message.path("msg"));
                    if (decision == null) {
                        return;
                    }
                    String payload = decision == TopicDecision.CHANGED ? full(message) : truncate(message);
                    log.info(in(">>>>> 【接收|{}】机器人→上位机 | op=publish | topic={} | payload={}",
                            decision.label(), topic, payload));
                } else {
                    // 传感器流（雷达/位姿/tf/odom/关节…）：固定间隔摘要，无变化分析价值
                    if (!allowSensor("in:" + topic)) {
                        return;
                    }
                    log.info(in(">>>>> 【接收|节流】机器人→上位机 | op=publish | topic={} | payload={}",
                            topic, truncate(message)));
                }
            }
            // action_feedback（400ms 进度流）：按传感器流固定节流
            case "action_feedback" -> {
                String id = message.path("id").asText("");
                if (!allowSensor("in:feedback:" + id)) {
                    return;
                }
                log.info(in(">>>>> 【接收|节流】机器人→上位机 | op=action_feedback | id={} | payload={}",
                        id, truncate(message)));
            }
            // service_response / action_result 等：指令回包（一次性），必打且全量，绝不漏
            default -> {
                if (!commandsEnabled) {
                    return;
                }
                log.info(in(">>>>> 【接收】机器人→上位机 | op={} | payload={}", op, full(message)));
            }
        }
    }

    // ==================== 节流 ====================

    /** 节流决策：CHANGED=内容变化（全量打印）/ UNCHANGED=内容未变化（保底摘要）。 */
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

    /** 每个话题的节流状态：上次打印的规范化内容 + 打印时间。 */
    private static final class TopicState {
        String lastContent;
        long lastLoggedMs;
    }

    /** 传感器流固定节流：同一 key 每 sensorIntervalMs（默认 30s）最多 1 条摘要。 */
    private static boolean allowSensor(String key) {
        long interval = sensorIntervalMs;
        if (!topicEnabled || interval <= 0) {
            return false;
        }
        return allowByTime(key, interval);
    }

    /**
     * 业务状态话题内容感知节流：与上次打印的规范化内容相同 → 按 unchangedIntervalMs 保底；
     * 不同（真实变化）→ 按 topicIntervalMs 节流。
     *
     * @return null=本次跳过；CHANGED=内容变化（调用方应打全量）；UNCHANGED=保底（调用方打摘要）
     */
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

    /** 时间节流：同一 key 在 interval 内最多打 1 条（首条必打）。 */
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

    /**
     * 规范化报文用于变化比较：
     * - 删除所有 {@code stamp} 字段（header.stamp / transforms[].header.stamp 每条都变）；
     * - 浮点四舍五入到 2 位小数（电量缓涨 24.983→24.98、位姿亚厘米抖动不误判为变化）；
     * - 其余字段（state / mode / charging 等）原样保留——真正的业务变化必被捕获。
     */
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

    /** 摘要打印：截断到 maxChars（默认 300 字符），用于传感器流节流与未变化保底。 */
    private static String truncate(JsonNode node) {
        String text = node.toString();
        int limit = maxChars;
        return text.length() <= limit ? text : text.substring(0, limit) + "…(共 " + text.length() + " 字符)";
    }

    /** 全量打印：变化内容/指令回包完整输出（上限 fullChars，默认 64KB，防 MB 级报文打爆日志）。 */
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

