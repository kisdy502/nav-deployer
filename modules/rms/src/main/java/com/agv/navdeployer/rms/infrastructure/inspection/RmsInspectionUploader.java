package com.agv.navdeployer.rms.infrastructure.inspection;

import com.agv.navdeployer.rms.infrastructure.config.RmsProperties;
import com.agv.navdeployer.rms.inspection.RmsInspectionResultFactory;
import com.agv.navdeployer.rms.infrastructure.protocol.dto.command.BodyReply;
import com.agv.navdeployer.rms.domain.RmsTaskRegistry;
import com.agv.navdeployer.rms.infrastructure.zenoh.PresignedUrlPut;
import com.agv.navdeployer.rms.infrastructure.zenoh.RmsMessageLog;
import com.agv.navdeployer.rms.infrastructure.zenoh.ZenohChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

public class RmsInspectionUploader {

    private static final Logger log = LoggerFactory.getLogger(RmsInspectionUploader.class);

    private final ObjectMapper mapper;
    private final RmsProperties properties;
    private final RmsTaskRegistry taskRegistry;
    private final java.util.concurrent.ExecutorService uploadWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "rms-inspection-upload");
                thread.setDaemon(true);
                return thread;
            });

    public RmsInspectionUploader(ObjectMapper mapper,
                                 RmsProperties properties,
                                 RmsTaskRegistry taskRegistry) {
        this.mapper = mapper;
        this.properties = properties;
        this.taskRegistry = taskRegistry;
    }

    public BodyReply handleUploadStart(String rawPayload, ZenohChannel channel, String publishKeyBase) {
        String taskId;
        String pathUrl;
        try {
            JsonNode root = mapper.readTree(rawPayload == null || rawPayload.isBlank() ? "{}" : rawPayload);
            taskId = text(root, "task_id");
            pathUrl = text(root, "path_url");
            if (taskId == null || pathUrl == null) {
                return BodyReply.failure(400, "task_id 和 path_url 必填");
            }
        } catch (Exception exception) {
            return BodyReply.failure(400, "payload 解析失败: " + exception.getMessage());
        }

        String finalTaskId = taskId;
        String finalPathUrl = pathUrl;
        uploadWorker.execute(() -> {
            try {
                byte[] zip = buildInspectionZip(finalTaskId);
                uploadToUrl(finalPathUrl, zip);
                log.info("质检结果文件上传成功: task_id={} url={} size={}bytes", finalTaskId, finalPathUrl, zip.length);
                publishUploadedNotification(channel, publishKeyBase, finalTaskId, "inspection_result.zip");
            } catch (Exception exception) {
                log.warn("质检结果文件上传失败: task_id={} pathUrl={} msg={}", finalTaskId, finalPathUrl, exception.getMessage());
                publishUploadFailedNotification(channel, publishKeyBase, finalTaskId, exception.getMessage());
            }
        });
        return BodyReply.success("upload accepted", null);
    }

    byte[] buildInspectionZip(String taskId) throws Exception {
        Map<String, Object> parameters = taskRegistry.find(taskId)
                .map(task -> task.parameters())
                .orElseGet(Map::of);
        Map<String, Object> report = RmsInspectionResultFactory.create(
                taskId, properties.getBehavior().getInspectionResult(), parameters);
        String inspectionResult = String.valueOf(report.get("inspection_result"));

        String reportJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        String summary = "Simulation Inspection Report\n"
                + "Task ID: " + taskId + "\n"
                + "Result: " + inspectionResult + "\n"
                + "Generated: " + java.time.Instant.now() + "\n";

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("report.json"));
            zip.write(reportJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("summary.txt"));
            zip.write(summary.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            List<PickedImage> images = pickRandomImages();
            if (images.isEmpty()) {
                log.warn("质检 zip 未取到本机图片（dir={}），回退占位图", properties.getBehavior().getInspectionImageDir());
                writePlaceholderImage(zip, taskId + "_left_3D.jpg", true);
                writePlaceholderImage(zip, taskId + "_right_3D.jpg", "OK".equals(inspectionResult));
            } else {
                for (PickedImage image : images) {
                    zip.putNextEntry(new ZipEntry(image.entryName()));
                    zip.write(image.bytes());
                    zip.closeEntry();
                }
                log.info("质检 zip 已打包 {} 张本机图片: {}",
                        images.size(), images.stream().map(PickedImage::sourceName).toList());
            }
        }
        return buffer.toByteArray();
    }

    private record PickedImage(String entryName, String sourceName, byte[] bytes) {
    }

    private static final String[] IMAGE_EXTENSIONS = {"jpg", "jpeg", "png", "bmp", "gif", "webp"};

    private List<PickedImage> pickRandomImages() {
        String dir = properties.getBehavior().getInspectionImageDir();
        if (dir == null || dir.isBlank()) {
            return List.of();
        }
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            log.warn("质检图片目录不存在: {}", root);
            return List.of();
        }
        List<Path> candidates;
        try (var stream = Files.walk(root)) {
            candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isImageFile(path.getFileName().toString()))
                    .collect(java.util.stream.Collectors.toList());
        } catch (Exception exception) {
            log.warn("扫描质检图片目录失败: {} msg={}", root, exception.getMessage());
            return List.of();
        }
        if (candidates.isEmpty()) {
            log.warn("质检图片目录及其子目录没有图片: {}", root);
            return List.of();
        }
        java.util.Collections.shuffle(candidates);
        int count = Math.min(2 + java.util.concurrent.ThreadLocalRandom.current().nextInt(2), candidates.size());
        List<PickedImage> picked = new ArrayList<>(count);
        for (Path path : candidates) {
            if (picked.size() >= count) {
                break;
            }
            try {
                String fileName = path.getFileName().toString();
                String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                picked.add(new PickedImage(
                        "inspection_image_%d.%s".formatted(picked.size() + 1, ext),
                        fileName,
                        Files.readAllBytes(path)));
            } catch (Exception exception) {
                log.warn("读取质检图片失败，跳过: {} msg={}", path, exception.getMessage());
            }
        }
        return picked;
    }

    private static boolean isImageFile(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String candidate : IMAGE_EXTENSIONS) {
            if (candidate.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    private static void writePlaceholderImage(ZipOutputStream zip,
                                              String fileName,
                                              boolean passed) throws Exception {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(passed ? new Color(46, 160, 67) : new Color(218, 54, 51));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", imageBytes);
        zip.putNextEntry(new ZipEntry(fileName));
        zip.write(imageBytes.toByteArray());
        zip.closeEntry();
    }

    void uploadToUrl(String url, byte[] body) throws Exception {
        PresignedUrlPut.put(url, body);
    }

    void publishUploadedNotification(ZenohChannel channel, String keyBase, String taskId, String fileName) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("task_id", taskId);
            root.put("result", "success");
            root.put("msg", "upload completed");
            ObjectNode data = root.putObject("data");
            ArrayNode files = data.putArray("files");
            ObjectNode file = files.addObject();
            file.put("file_name", fileName);
            file.put("upload_status", "uploaded");

            String key = keyBase + "/task-result-files-uploaded";
            String notification = mapper.writeValueAsString(root);
            RmsMessageLog.outboundPublish(key, notification);
            channel.putOnce(key, notification);
            log.info("task-result-files-uploaded 已通知: task_id={} key={}", taskId, key);
        } catch (Exception exception) {
            log.warn("task-result-files-uploaded 通知失败: task_id={} msg={}", taskId, exception.getMessage());
        }
    }

    void publishUploadFailedNotification(ZenohChannel channel, String keyBase, String taskId, String reason) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("task_id", taskId);
            root.put("result", "failed");
            root.put("msg", reason == null ? "upload failed" : reason);
            String key = keyBase + "/task-result-files-uploaded";
            String payload = mapper.writeValueAsString(root);
            channel.putOnce(key, payload);
            com.agv.navdeployer.rms.infrastructure.zenoh.RmsMessageLog.outboundPublish(key, payload);
            log.info("task-result-files-uploaded(failed) 已通知: task_id={} key={}", taskId, key);
        } catch (Exception exception) {
            log.warn("task-result-files-uploaded(failed) 通知失败: task_id={} msg={}", taskId, exception.getMessage());
        }
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
