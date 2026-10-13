package com.agv.navdeployer.rms.zenoh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ansi.AnsiOutput;

/**
 * RMS zenoh 关键消息高亮日志：任务下发/任务上报/状态上报/查询响应等低频消息打完整报文。
 *
 * <p>格式约定（一眼可辨方向与类型）：
 * <pre>
 * >>>>> 【下行|查询】RMS→上位机    （RMS query 上位机：task/add、task/start、result_files_upload_start…）
 * <<<<< 【上行|响应】上位机→RMS    （上位机 reply RMS 的查询）
 * >>>>> 【下行|发布】RMS→上位机    （RMS 发布、上位机订阅收到）
 * >>>>> 【下行|响应】RMS→上位机    （RMS reply 上位机的查询）
 * <<<<< 【上行|发布】上位机→RMS    （result_report、status_report、task-result-files-uploaded…）
 * <<<<< 【上行|查询】上位机→RMS    （register 等上位机主动查询）
 * </pre>
 *
 * <p>颜色：下行黄色、上行青色，随 spring.output.ansi.enabled=always 生效（IDEA 控制台）；
 * 文件 appender 的 %replace 会剥离颜色码，文件保持干净。颜色关闭时仅剩 >>>>>/<<<<< 标记，仍然醒目。
 */
public final class RmsMessageLog {

    private static final Logger log = LoggerFactory.getLogger("RMS-ZENOH");

    private static final String RESET = "\u001B[0m";
    /** 下行：亮黄加粗 */
    private static final String DOWN_COLOR = "\u001B[1;93m";
    /** 上行：亮青加粗 */
    private static final String UP_COLOR = "\u001B[1;96m";

    private static volatile Boolean ansiEnabled;

    private RmsMessageLog() {
    }

    /** 下行·查询：RMS 查询上位机（上位机是 queryable 应答方） */
    public static void inboundQuery(String key, String payload) {
        log.info(down(">>>>> 【下行|查询】RMS→上位机 | key={} | payload={}", key, payload));
    }

    /** 上行·响应：上位机应答 RMS 的查询 */
    public static void outboundReply(String key, String reply) {
        log.info(up("<<<<< 【上行|响应】上位机→RMS | key={} | reply={}", key, reply));
    }

    /** 下行·发布：RMS 发布、上位机订阅收到 */
    public static void inboundPublish(String key, String payload) {
        log.info(down(">>>>> 【下行|发布】RMS→上位机(订阅) | key={} | payload={}", key, payload));
    }

    /** 下行·响应：RMS 应答上位机的查询 */
    public static void inboundReply(String key, String reply) {
        log.info(down(">>>>> 【下行|响应】RMS→上位机 | key={} | reply={}", key, reply));
    }

    /** 上行·发布：上位机发布上报（result_report / status_report / task-result-files-uploaded…） */
    public static void outboundPublish(String key, String payload) {
        log.info(up("<<<<< 【上行|发布】上位机→RMS | key={} | payload={}", key, payload));
    }

    /** 上行·查询：上位机主动查询 RMS（register 等） */
    public static void outboundQuery(String key, String payload) {
        log.info(up("<<<<< 【上行|查询】上位机→RMS | key={} | payload={}", key, payload));
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

    /** SLF4J 风格 {} 占位符替换（String.format 只认 %s，不能用于 {} 模板） */
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

    /** 惰性判定（首次使用时环境已就绪）：仅 spring.output.ansi.enabled=always 时嵌入颜色码 */
    private static boolean ansiEnabled() {
        Boolean cached = ansiEnabled;
        if (cached == null) {
            cached = AnsiOutput.getEnabled() == AnsiOutput.Enabled.ALWAYS;
            ansiEnabled = cached;
        }
        return cached;
    }
}
