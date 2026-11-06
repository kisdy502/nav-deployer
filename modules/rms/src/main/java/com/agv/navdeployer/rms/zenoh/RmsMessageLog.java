package com.agv.navdeployer.rms.zenoh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ansi.AnsiOutput;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RmsMessageLog {

    private static final Logger log = LoggerFactory.getLogger("RMS-ZENOH");

    private static final String RESET = "\u001B[0m";
    private static final String DOWN_COLOR = "\u001B[1;93m";
    private static final String UP_COLOR = "\u001B[1;96m";

    private static final ConcurrentHashMap<String, AtomicLong> lastLoggedAt = new ConcurrentHashMap<>();
    private static volatile Boolean ansiEnabled;

    private RmsMessageLog() {
    }

    public static void inboundQuery(String key, String payload) {
        log.info(down(">>>>> 【下行|查询】RMS→上位机 | key={} | payload={}", key, payload));
    }

    public static void outboundReply(String key, String reply) {
        log.info(up("<<<<< 【上行|响应】上位机→RMS | key={} | reply={}", key, reply));
    }

    public static void inboundQueryThrottled(String key, String payload, long intervalMs) {
        if (!allowByTime("q:" + key, intervalMs)) {
            return;
        }
        log.info(down(">>>>> 【下行|查询|{}s节流】RMS→上位机 | key={} | payload={}",
                intervalMs / 1000, key, payload));
    }

    public static void outboundReplyThrottled(String key, String reply, long intervalMs) {
        if (!allowByTime("r:" + key, intervalMs)) {
            return;
        }
        log.info(up("<<<<< 【上行|响应|{}s节流】上位机→RMS | key={} | reply={}",
                intervalMs / 1000, key, reply));
    }

    public static void inboundPublish(String key, String payload) {
        log.info(down(">>>>> 【下行|发布】RMS→上位机(订阅) | key={} | payload={}", key, payload));
    }

    public static void inboundReply(String key, String reply) {
        log.info(down(">>>>> 【下行|响应】RMS→上位机 | key={} | reply={}", key, reply));
    }

    public static void outboundPublish(String key, String payload) {
        log.info(up("<<<<< 【上行|发布】上位机→RMS | key={} | payload={}", key, payload));
    }

    public static void outboundQuery(String key, String payload) {
        log.info(up("<<<<< 【上行|查询】上位机→RMS | key={} | payload={}", key, payload));
    }

    private static boolean allowByTime(String key, long intervalMs) {
        if (intervalMs <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        AtomicLong last = lastLoggedAt.computeIfAbsent(key, k -> new AtomicLong(0));
        long prev = last.get();
        return now - prev >= intervalMs && last.compareAndSet(prev, now);
    }

    private static String down(String format, Object... args) {
        return color(DOWN_COLOR, format, args);
    }

    private static String up(String format, Object... args) {
        return color(UP_COLOR, format, args);
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
