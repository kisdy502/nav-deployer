package com.agv.navdeployer.rms.log;

import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.protocol.dto.command.BodyReply;
import com.agv.navdeployer.rms.zenoh.RmsMessageLog;
import com.agv.navdeployer.rms.zenoh.ZenohChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * RMS 日志收集：云端 query log/collect（受理应答）→ 本机快照打包日志 → PUT 预签名 URL
 * → pub log/report 上报结果。协议对齐 robot-management 的
 * RobotLogCollectionServiceImpl（指令/受理校验）与 RobotLogResultSubscriber（结果校验）。
 *
 * <p>受理在 queryable 线程内同步快答（云端 query 有等待窗口，绝不阻塞），
 * 快照+压缩+上传在单线程 daemon 执行器中异步完成。
 */
public class RmsLogCollector {

    private static final Logger log = LoggerFactory.getLogger(RmsLogCollector.class);

    /** 默认日志文件（与 logback APP_LOG_FILE 默认一致；LOG_FILE 环境变量优先） */
    private static final String DEFAULT_LOG_FILE = "logs/nav-deployer.log";
    /** 打包进 zip 的最大滚动文件个数（当前文件之外） */
    private static final int MAX_ROLLED_FILES = 4;
    /** zip 累计原始大小上限，防止日志暴涨撑爆内存 */
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    /** 受理前的最小剩余磁盘空间 */
    private static final long MIN_FREE_DISK_BYTES = 200L * 1024 * 1024;
    private static final long HTTP_TIMEOUT_SEC = 60;

    private final ObjectMapper mapper;
    private final RmsProperties properties;
    private final HttpClient httpClient;
    /** 单飞：同一时刻只允许一个进行中的收集（云端 COLLECTION_BUSY 约定） */
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rms-log-collect");
        thread.setDaemon(true);
        return thread;
    });

    public RmsLogCollector(ObjectMapper mapper, RmsProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** 处理云端 log/collect 指令：同步受理应答，异步执行快照/上传/上报。 */
    public BodyReply handleCollect(String rawPayload, ZenohChannel channel, String keyBase) {
        Command command;
        try {
            command = parseCommand(rawPayload);
        } catch (IllegalArgumentException exception) {
            log.warn("log/collect 指令解析失败: {}", exception.getMessage());
            return reject(400, "INVALID_REQUEST", rawCollectionId(rawPayload));
        }
        if (!properties.getRobot().getRobotSn().equals(command.serialNum())) {
            log.warn("log/collect serial_num 不匹配: expected={} actual={}",
                    properties.getRobot().getRobotSn(), command.serialNum());
            return reject(403, "ROBOT_IDENTITY_MISMATCH", command.collectionId());
        }
        if (!inFlight.compareAndSet(false, true)) {
            log.warn("log/collect 拒绝：已有进行中的收集, collectionId={}", command.collectionId());
            return reject(409, "COLLECTION_BUSY", command.collectionId());
        }
        try {
            Path logFile = resolveLogFile();
            if (logFile.getParent() != null) {
                long free = logFile.getParent().toFile().getUsableSpace();
                if (free >= 0 && free < MIN_FREE_DISK_BYTES) {
                    return reject(507, "INSUFFICIENT_DISK_SPACE", command.collectionId());
                }
            }
        } catch (Exception exception) {
            log.warn("log/collect 磁盘空间检查失败（忽略继续）: {}", exception.getMessage());
        }

        Command accepted = command;
        worker.execute(() -> execute(accepted, channel, keyBase));
        log.info("log/collect 已受理: collectionId={}, fileName={}, url_expires_at={}",
                command.collectionId(), command.fileName(),
                Instant.ofEpochMilli(command.expiresAt()));
        return BodyReply.success("accepted", Map.of(
                "collection_id", command.collectionId(),
                "accepted", Boolean.TRUE));
    }

    // ==================== 异步执行 ====================

    private void execute(Command command, ZenohChannel channel, String keyBase) {
        try {
            byte[] zip = snapshotLogs(command);
            String sha256 = sha256Hex(zip);
            upload(command, zip);
            publishSuccess(command, zip.length, sha256, channel, keyBase);
            log.info("日志收集完成: collectionId={}, size={}bytes", command.collectionId(), zip.length);
        } catch (CollectException exception) {
            log.warn("日志收集失败: collectionId={}, stage={}, error_code={}, msg={}",
                    command.collectionId(), exception.stage(), exception.errorCode(), exception.getMessage());
            publishFailure(command, exception.stage(), exception.errorCode(), channel, keyBase);
        } catch (Exception exception) {
            log.warn("日志收集异常: collectionId={}, msg={}", command.collectionId(), exception.getMessage(), exception);
            publishFailure(command, "collect", "INTERNAL_ERROR", channel, keyBase);
        } finally {
            inFlight.set(false);
        }
    }

    /** 快照打包：当前日志 + 最近滚动文件 + application.yaml + 运行信息。 */
    private byte[] snapshotLogs(Command command) throws CollectException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            long total = 0;
            for (Path file : snapshotCandidates()) {
                byte[] bytes = Files.readAllBytes(file);
                if (total + bytes.length > MAX_TOTAL_BYTES) {
                    log.warn("日志快照超过大小上限，跳过剩余文件: skipped={}", file.getFileName());
                    break;
                }
                total += bytes.length;
                zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
                zip.write(bytes);
                zip.closeEntry();
            }
            byte[] config = readClasspath("application.yaml");
            if (config != null) {
                zip.putNextEntry(new ZipEntry("application.yaml"));
                zip.write(config);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("runtime-info.txt"));
            zip.write(runtimeInfo(command).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (Exception exception) {
            throw new CollectException("collect", "LOG_COLLECTION_FAILED",
                    "快照日志失败: " + exception.getMessage(), exception);
        }
        return buffer.toByteArray();
    }

    private void upload(Command command, byte[] body) throws CollectException {
        if (System.currentTimeMillis() > command.expiresAt()) {
            throw new CollectException("upload", "UPLOAD_URL_EXPIRED", "上传地址已过期", null);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(command.url()))
                .header("Content-Type", command.contentType())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SEC))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new CollectException("upload", "OBJECT_STORAGE_UPLOAD_FAILED",
                        "HTTP " + response.statusCode() + ": "
                                + (response.body() == null ? "" : response.body().substring(0,
                                Math.min(200, response.body().length()))), null);
            }
        } catch (CollectException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CollectException("upload", "OBJECT_STORAGE_UPLOAD_FAILED",
                    "上传失败: " + exception.getMessage(), exception);
        }
    }

    // ==================== 结果上报 ====================

    private void publishSuccess(Command command, long fileSize, String sha256,
                                 ZenohChannel channel, String keyBase) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("status", 0);
            root.put("msg", "log collected");
            root.put("timestamp", Instant.now().toEpochMilli());
            ObjectNode data = root.putObject("data");
            data.put("collection_id", command.collectionId());
            data.put("file_name", command.fileName());
            data.put("file_size", fileSize);
            data.put("sha256", sha256);
            publish(root, channel, keyBase);
        } catch (Exception exception) {
            log.warn("log/report 成功上报失败: collectionId={}, msg={}",
                    command.collectionId(), exception.getMessage());
        }
    }

    private void publishFailure(Command command, String stage, String errorCode,
                                ZenohChannel channel, String keyBase) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("status", 1);
            root.put("msg", "log collect failed");
            root.put("timestamp", Instant.now().toEpochMilli());
            ObjectNode data = root.putObject("data");
            data.put("collection_id", command.collectionId());
            data.put("stage", stage);
            data.put("error_code", errorCode);
            publish(root, channel, keyBase);
        } catch (Exception exception) {
            log.warn("log/report 失败上报失败: collectionId={}, msg={}",
                    command.collectionId(), exception.getMessage());
        }
    }

    private void publish(ObjectNode root, ZenohChannel channel, String keyBase) throws Exception {
        String key = keyBase + "/log/report";
        String payload = mapper.writeValueAsString(root);
        RmsMessageLog.outboundPublish(key, payload);
        channel.putOnce(key, payload);
    }

    // ==================== 辅助 ====================

    private record Command(String collectionId, String serialNum, String fileName,
                           String url, String contentType, long expiresAt) {
    }

    private Command parseCommand(String rawPayload) {
        JsonNode root;
        try {
            root = mapper.readTree(rawPayload == null || rawPayload.isBlank() ? "{}" : rawPayload);
        } catch (Exception exception) {
            throw new IllegalArgumentException("payload 不是合法 JSON");
        }
        String collectionId = text(root, "collection_id");
        String serialNum = text(root, "serial_num");
        String fileName = text(root, "file_name");
        String url = text(root, "url");
        String contentType = text(root, "content_type");
        if (collectionId == null || serialNum == null || fileName == null
                || url == null || contentType == null) {
            throw new IllegalArgumentException("collection_id/serial_num/file_name/url/content_type 必填");
        }
        long expiresAt = root.path("expires_at").asLong(0);
        if (expiresAt <= 0) {
            throw new IllegalArgumentException("expires_at 非法");
        }
        return new Command(collectionId, serialNum, fileName, url, contentType, expiresAt);
    }

    private BodyReply reject(int status, String errorCode, String collectionId) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (collectionId != null) {
            data.put("collection_id", collectionId);
        }
        data.put("accepted", Boolean.FALSE);
        data.put("error_code", errorCode);
        return BodyReply.failure(status, "rejected: " + errorCode, data);
    }

    private String rawCollectionId(String rawPayload) {
        try {
            return text(mapper.readTree(rawPayload == null || rawPayload.isBlank() ? "{}" : rawPayload),
                    "collection_id");
        } catch (Exception exception) {
            return null;
        }
    }

    private Path resolveLogFile() {
        String configured = System.getenv("LOG_FILE");
        return Path.of(configured == null || configured.isBlank() ? DEFAULT_LOG_FILE : configured.trim());
    }

    /** 当前日志 + 最近滚动文件（读取字节即快照，logback 继续写不影响已读内容）。 */
    private List<Path> snapshotCandidates() {
        Path main = resolveLogFile();
        List<Path> candidates = new ArrayList<>();
        if (Files.isRegularFile(main)) {
            candidates.add(main);
        }
        Path dir = main.getParent();
        String prefix = main.getFileName().toString();
        if (dir != null && Files.isDirectory(dir)) {
            try (Stream<Path> siblings = Files.list(dir)) {
                siblings.filter(Files::isRegularFile)
                        .filter(path -> !path.equals(main))
                        .filter(path -> path.getFileName().toString().startsWith(prefix))
                        .sorted(Comparator.comparingLong((Path path) -> {
                            try {
                                return Files.getLastModifiedTime(path).toMillis();
                            } catch (Exception exception) {
                                return 0L;
                            }
                        }).reversed())
                        .limit(MAX_ROLLED_FILES)
                        .forEach(candidates::add);
            } catch (Exception exception) {
                log.warn("扫描滚动日志失败（忽略）: {}", exception.getMessage());
            }
        }
        return candidates;
    }

    private byte[] readClasspath(String name) {
        try (var in = getClass().getClassLoader().getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception exception) {
            return null;
        }
    }

    private String runtimeInfo(Command command) {
        Path logFile = resolveLogFile();
        long usable = logFile.getParent() == null ? -1 : logFile.getParent().toFile().getUsableSpace();
        return "collection_id: " + command.collectionId() + "\n"
                + "time: " + Instant.now() + "\n"
                + "java: " + System.getProperty("java.version") + "\n"
                + "os: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\n"
                + "log_file: " + logFile.toAbsolutePath().normalize() + "\n"
                + "free_disk_bytes: " + usable + "\n";
    }

    private String sha256Hex(byte[] body) throws CollectException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body)).toLowerCase(Locale.ROOT);
        } catch (Exception exception) {
            throw new CollectException("compress", "LOG_COMPRESSION_FAILED",
                    "计算SHA-256失败: " + exception.getMessage(), exception);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null : value.asText().trim();
    }

    /** 业务失败：stage ∈ collect/compress/upload，error_code 对齐云端白名单。 */
    private static final class CollectException extends Exception {
        private final String stage;
        private final String errorCode;

        CollectException(String stage, String errorCode, String message, Throwable cause) {
            super(message, cause);
            this.stage = stage;
            this.errorCode = errorCode;
        }

        String stage() {
            return stage;
        }

        String errorCode() {
            return errorCode;
        }
    }
}
