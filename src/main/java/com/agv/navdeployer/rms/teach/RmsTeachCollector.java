package com.agv.navdeployer.rms.teach;

import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.zenoh.PresignedUrlPut;
import com.agv.navdeployer.rms.zenoh.RmsMessageLog;
import com.agv.navdeployer.rms.zenoh.ZenohChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * RMS teach 采集闭环：云端 query teach/capture（受理应答）→ 从样例目录打包 zip
 * → PUT 云端给的 upload_url → pub teach/result_2d3d_files_uploaded 上报上传结果。
 *
 * <p>应答信封为 teach 协议自定义格式 {msg_id, status, msg, timestamp, data}
 * （区别于 BodyReply）：msg_id 回显请求序列号，status=0 受理成功；
 * 上传成败经 result_2d3d_files_uploaded 发布（status=0 成功 / 1 失败），实现 2D/3D 数据包闭环。
 *
 * <p>受理在 queryable 线程内同步快答（云端 query 有等待窗口，绝不阻塞），
 * 打包+上传在单线程 daemon 执行器中异步完成。
 */
public class RmsTeachCollector {

    private static final Logger log = LoggerFactory.getLogger(RmsTeachCollector.class);

    /** 协议约定的 camera 取值范围 */
    private static final Set<String> CAMERAS = Set.of(
            "left-1", "left-2", "left-3", "right-1", "right-2", "right-3", "head", "front", "back");
    /** 从样例目录最多取的文件个数 */
    private static final int MAX_SAMPLE_FILES = 5;
    /** zip 累计原始大小上限 */
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    /** msg_id 范围 1 ~ 2,147,483,647（协议约定）；请求未携带时本地生成 */
    private static final long MSG_ID_MAX = 2_147_483_647L;

    private final ObjectMapper mapper;
    private final RmsProperties properties;
    /** 单飞：同一时刻只允许一个进行中的采集 */
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicLong msgIdSeq = new AtomicLong(1);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rms-teach-capture");
        thread.setDaemon(true);
        return thread;
    });

    public RmsTeachCollector(ObjectMapper mapper, RmsProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    /**
     * 处理云端 teach/capture 指令：校验必填参数后同步受理应答，异步执行打包/上传/上报。
     * 返回 teach 协议信封（ObjectNode），由 RmsCommandGateway 直接序列化回复。
     */
    public ObjectNode handleCapture(String rawPayload, ZenohChannel channel, String keyBase) {
        JsonNode root;
        try {
            root = mapper.readTree(rawPayload == null || rawPayload.isBlank() ? "{}" : rawPayload);
        } catch (Exception exception) {
            log.warn("teach/capture 指令解析失败: {}", exception.getMessage());
            return envelope(nextMsgId(), 400, "payload 不是合法 JSON", null);
        }
        long msgId = root.path("msg_id").asLong(0);
        if (msgId <= 0) {
            msgId = nextMsgId();
        }
        String template = text(root, "template");
        String node = text(root, "node");
        String camera = text(root, "camera");
        String uploadUrl = text(root, "upload_url");
        if (template == null || node == null || camera == null || uploadUrl == null) {
            return envelope(msgId, 400, "template/node/camera/upload_url 必填", null);
        }
        if (!CAMERAS.contains(camera)) {
            return envelope(msgId, 400,
                    "camera 取值非法: " + camera + "（允许 left-1~3/right-1~3/head/front/back）", null);
        }
        if (!inFlight.compareAndSet(false, true)) {
            return envelope(msgId, 409, "已有进行中的采集，请稍后再试", null);
        }

        Command command = new Command(msgId, template, node, camera, uploadUrl);
        worker.execute(() -> execute(command, channel, keyBase));
        log.info("teach/capture 已受理: msg_id={} template={} node={} camera={}",
                msgId, template, node, camera);
        ObjectNode data = mapper.createObjectNode();
        data.put("template", template);
        data.put("node", node);
        data.put("camera", camera);
        data.put("accepted", true);
        return envelope(msgId, 0, "accepted", data);
    }

    // ==================== 异步执行 ====================

    private void execute(Command command, ZenohChannel channel, String keyBase) {
        try {
            byte[] zip = buildZip(command);
            String sha256 = sha256Hex(zip);
            PresignedUrlPut.put(command.uploadUrl(), zip);
            log.info("teach 采集上传完成: msg_id={} camera={} size={}bytes",
                    command.msgId(), command.camera(), zip.length);
            publishResult(command, 0, "upload completed", zip.length, sha256, channel, keyBase);
        } catch (Exception exception) {
            log.warn("teach 采集上传失败: msg_id={} msg={}", command.msgId(), exception.getMessage());
            publishResult(command, 1, "upload failed: " + exception.getMessage(),
                    0, null, channel, keyBase);
        } finally {
            inFlight.set(false);
        }
    }

    /** 采集 zip：teach_capture.json 元信息 + 样例目录文件（无目录/为空回退 application.yaml + runtime 信息）。 */
    byte[] buildZip(Command command) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            ObjectNode meta = mapper.createObjectNode();
            meta.put("msg_id", command.msgId());
            meta.put("template", command.template());
            meta.put("node", command.node());
            meta.put("camera", command.camera());
            meta.put("generated_at", Instant.now().toString());
            zip.putNextEntry(new ZipEntry("teach_capture.json"));
            zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(meta));
            zip.closeEntry();

            List<Path> samples = sampleFiles();
            if (samples.isEmpty()) {
                log.warn("teach 样例目录为空（dir={}），回退 application.yaml + runtime-info",
                        properties.getBehavior().getTeachSampleDir());
                byte[] config = readClasspath("application.yaml");
                if (config != null) {
                    zip.putNextEntry(new ZipEntry("application.yaml"));
                    zip.write(config);
                    zip.closeEntry();
                }
                zip.putNextEntry(new ZipEntry("runtime-info.txt"));
                zip.write(runtimeInfo().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            } else {
                long total = 0;
                for (Path file : samples) {
                    byte[] bytes = Files.readAllBytes(file);
                    if (total + bytes.length > MAX_TOTAL_BYTES) {
                        log.warn("teach zip 超过大小上限，跳过剩余文件: skipped={}", file.getFileName());
                        break;
                    }
                    total += bytes.length;
                    zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
                    zip.write(bytes);
                    zip.closeEntry();
                }
            }
        }
        return buffer.toByteArray();
    }

    /** 样例目录（含子目录）按文件名排序取前 MAX_SAMPLE_FILES 个常规文件；目录未配置/不存在返回空。 */
    private List<Path> sampleFiles() {
        String dir = properties.getBehavior().getTeachSampleDir();
        if (dir == null || dir.isBlank()) {
            return List.of();
        }
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            log.warn("teach 样例目录不存在: {}", root);
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .limit(MAX_SAMPLE_FILES)
                    .toList();
        } catch (Exception exception) {
            log.warn("扫描 teach 样例目录失败: {} msg={}", root, exception.getMessage());
            return List.of();
        }
    }

    // ==================== 结果上报 ====================

    private void publishResult(Command command, int status, String msg, long fileSize,
                               String sha256, ZenohChannel channel, String keyBase) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("template", command.template());
            root.put("node", command.node());
            root.put("camera", command.camera());
            root.put("msg_id", command.msgId());
            root.put("status", status);
            root.put("msg", msg);
            root.put("timestamp", Instant.now().toEpochMilli());
            ObjectNode data = root.putObject("data");
            data.put("file_name", "teach_capture.zip");
            data.put("file_size", fileSize);
            if (sha256 != null) {
                data.put("sha256", sha256);
            }
            String key = keyBase + "/teach/result_2d3d_files_uploaded";
            String payload = mapper.writeValueAsString(root);
            RmsMessageLog.outboundPublish(key, payload);
            channel.putOnce(key, payload);
            log.info("teach/result_2d3d_files_uploaded 已通知: msg_id={} status={} key={}",
                    command.msgId(), status, key);
        } catch (Exception exception) {
            log.warn("teach/result_2d3d_files_uploaded 通知失败: msg_id={} msg={}",
                    command.msgId(), exception.getMessage());
        }
    }

    // ==================== 辅助 ====================

    record Command(long msgId, String template, String node, String camera, String uploadUrl) {
    }

    /** teach 协议应答信封：{msg_id, status, msg, timestamp, data}，msg_id 与请求一致。 */
    private ObjectNode envelope(long msgId, int status, String msg, ObjectNode data) {
        ObjectNode root = mapper.createObjectNode();
        root.put("msg_id", msgId);
        root.put("status", status);
        root.put("msg", msg);
        root.put("timestamp", Instant.now().toEpochMilli());
        root.set("data", data == null ? mapper.createObjectNode() : data);
        return root;
    }

    private long nextMsgId() {
        return msgIdSeq.updateAndGet(prev -> prev >= MSG_ID_MAX ? 1 : prev + 1);
    }

    private String sha256Hex(byte[] body) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(body)).toLowerCase(Locale.ROOT);
    }

    private byte[] readClasspath(String name) {
        try (var in = getClass().getClassLoader().getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception exception) {
            return null;
        }
    }

    private String runtimeInfo() {
        return "time: " + Instant.now() + "\n"
                + "java: " + System.getProperty("java.version") + "\n"
                + "os: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\n";
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null : value.asText().trim();
    }
}
