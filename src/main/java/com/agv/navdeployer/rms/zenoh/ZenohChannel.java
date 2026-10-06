package com.agv.navdeployer.rms.zenoh;

import com.agv.navdeployer.rms.config.RmsProperties;
import io.zenoh.Config;
import io.zenoh.Session;
import io.zenoh.Zenoh;
import io.zenoh.bytes.ZBytes;
import io.zenoh.keyexpr.KeyExpr;
import io.zenoh.pubsub.Publisher;
import io.zenoh.query.CallbackQueryable;
import io.zenoh.query.GetOptions;
import io.zenoh.query.Query;
import io.zenoh.query.QueryableOptions;
import io.zenoh.query.Reply;
import io.zenoh.query.ReplyOptions;
import io.zenoh.sample.Sample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * zenoh 通道：Session 生命周期 + publisher/queryable 声明 + query 收发。
 * 会话由 {@code RmsRobotSession} 负责 rebuild（重连后重走声明与注册），
 * 本类只做资源管理与操作封装（对齐 RosbridgeClient 的角色划分）。
 *
 * <p>连接配置采用 zenoh 标准做法：classpath 下的 {@code config-json5.json}
 * （与 zenohd 同构的 JSON5），{@code rms.zenoh.config-file} 可指向外部文件覆盖
 * （部署换端点不用重打包）。yaml 里不再出现任何 zenoh 连接项。
 */
public final class ZenohChannel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ZenohChannel.class);
    private static final String CLASSPATH_CONFIG = "config-json5.json";

    private final String configJson5;
    private final List<AutoCloseable> resources = new ArrayList<>();
    private Session session;
    private volatile boolean open;

    public ZenohChannel(RmsProperties.Zenoh props) {
        this.configJson5 = loadConfigJson5(props);
    }

    /** 加载 zenoh 连接配置：外部文件（rms.zenoh.config-file）优先，否则 classpath config-json5.json。 */
    public static String loadConfigJson5(RmsProperties.Zenoh props) {
        String external = props.getConfigFile();
        if (external != null && !external.isBlank()) {
            try {
                String content = Files.readString(Path.of(external.trim()));
                log.info("rms zenoh config loaded from external file: {}", external.trim());
                return content;
            } catch (IOException exception) {
                throw new IllegalStateException("读取 zenoh 外部配置失败: " + external, exception);
            }
        }
        try (java.io.InputStream in = ZenohChannel.class.getClassLoader().getResourceAsStream(CLASSPATH_CONFIG)) {
            if (in == null) {
                throw new IllegalStateException("classpath 未找到 " + CLASSPATH_CONFIG
                        + "，且未配置 rms.zenoh.config-file");
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            log.info("rms zenoh config loaded from classpath: {}", CLASSPATH_CONFIG);
            return content;
        } catch (IOException exception) {
            throw new IllegalStateException("读取 classpath " + CLASSPATH_CONFIG + " 失败", exception);
        }
    }

    public synchronized void open() throws Exception {
        if (open) {
            return;
        }
        session = Zenoh.open(Config.fromJson5(configJson5));
        resources.add(session);
        open = true;
        log.info("rms zenoh session opened");
    }

    public boolean isOpen() {
        return open;
    }

    public synchronized void markBroken() {
        open = false;
    }

    @Override
    public synchronized void close() {
        open = false;
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Exception ignored) {
                // 关闭失败无需处理
            }
        }
        resources.clear();
        session = null;
    }

    /** 声明 publisher（资源纳入生命周期管理）。 */
    public synchronized void declarePublisher(String key) throws Exception {
        requireOpen();
        KeyExpr expr = KeyExpr.autocanonize(key);
        resources.add(expr);
        Publisher publisher = session.declarePublisher(expr);
        resources.add(publisher);
        log.debug("declared rms publisher key={}", key);
    }

    /** put 一条文本消息（每次声明临时 publisher，适合低频事件如 result_report）。 */
    public void putOnce(String key, String payload) throws Exception {
        requireOpen();
        try (KeyExpr expr = KeyExpr.autocanonize(key);
             Publisher publisher = session.declarePublisher(expr)) {
            publisher.put(payload);
        }
    }

    /** 声明 queryable（资源纳入生命周期管理）。 */
    public synchronized void declareQueryable(String key, Consumer<Query> handler) throws Exception {
        requireOpen();
        KeyExpr expr = KeyExpr.autocanonize(key);
        resources.add(expr);
        QueryableOptions options = new QueryableOptions(true);
        CallbackQueryable queryable = session.declareQueryable(expr, handler::accept, options);
        resources.add(queryable);
        log.info("declared rms queryable key={}", key);
    }

    /** query-reply：发送 payload，取第一条成功回复文本。 */
    public Optional<String> query(String key, String payload, long timeoutMs) throws Exception {
        requireOpen();
        GetOptions options = new GetOptions();
        options.setTimeout(Duration.ofMillis(Math.max(1000L, timeoutMs)));
        options.setPayload(ZBytes.from(payload.getBytes(StandardCharsets.UTF_8)));
        BlockingQueue<Optional<Reply>> replies;
        try (KeyExpr keyExpr = KeyExpr.tryFrom(key)) {
            replies = session.get(keyExpr, options);
        }
        Optional<Reply> first = replies.poll(timeoutMs + 500L, TimeUnit.MILLISECONDS);
        if (first == null || first.isEmpty()) {
            return Optional.empty();
        }
        Reply reply = first.get();
        if (reply instanceof Reply.Success success) {
            return Optional.of(readPayloadText(success.getSample()));
        }
        if (reply instanceof Reply.Error error) {
            log.warn("rms zenoh query error key={} error={}", key, readPayloadText(error.getError()));
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** query 回复（JSON 编码）。 */
    public static void replyJson(Query query, String json) {
        try {
            ReplyOptions options = new ReplyOptions();
            options.setEncoding(io.zenoh.bytes.Encoding.APPLICATION_JSON);
            query.reply(query.getKeyExpr(), json, options);
        } catch (Exception exception) {
            log.warn("failed to reply rms query key={} msg={}", query.getKeyExpr(), exception.getMessage());
        }
    }

    /** query 错误回复。 */
    public static void replyError(Query query, String message) {
        try {
            query.replyErr(message);
        } catch (Exception ignored) {
            // 连接已断，忽略
        }
    }

    public static String readPayload(Query query) {
        if (query == null) {
            return "{}";
        }
        try {
            ZBytes payload = query.getPayload();
            return payload == null ? "{}" : readPayloadText(payload);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private void requireOpen() {
        if (!open) {
            throw new IllegalStateException("rms zenoh channel is not open");
        }
    }

    private static String readPayloadText(Sample sample) {
        return sample == null ? "" : readPayloadText(sample.getPayload());
    }

    private static String readPayloadText(ZBytes bytes) {
        if (bytes == null) {
            return "";
        }
        String text = bytes.tryToString();
        return text != null ? text : new String(bytes.toBytes(), StandardCharsets.UTF_8);
    }

}
