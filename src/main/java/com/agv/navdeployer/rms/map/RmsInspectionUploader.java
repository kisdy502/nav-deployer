package com.agv.navdeployer.rms.map;

import com.agv.navdeployer.rms.protocol.dto.command.BodyReply;
import com.agv.navdeployer.rms.zenoh.ZenohChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * RMS 质检结果文件上传：收到云端 task/result_files_upload_start 指令后，
 * 生成质检报告 ZIP 并 HTTP PUT 到云端给的预签名 URL，
 * 完成后经 zenoh pub task-result-files-uploaded 通知云端。
 *
 * <p>云端源码参照 taskflow-management-service 的 TaskflowTaskResultZenohSubscriber：
 * ① result_report(completed + quality_inspection) 触发云端生成 S3 预签名 URL；
 * ② 云端 query task/result_files_upload_start {task_id, path_url} → 本类接收并上传；
 * ③ 本类 pub task-result-files-uploaded {task_id, result: "success"} → 云端标记 uploaded。
 */
public class RmsInspectionUploader {

    private static final Logger log = LoggerFactory.getLogger(RmsInspectionUploader.class);
    private static final long HTTP_TIMEOUT_SEC = 30;

    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public RmsInspectionUploader(ObjectMapper mapper) {
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 处理云端 task/result_files_upload_start 指令。
     * payload: {"task_id": "xxx", "path_url": "https://s3.../presigned"}
     * 同步生成 ZIP → 上传 → 回复 BodyReply → 异步 pub task-result-files-uploaded。
     */
    public BodyReply handleUploadStart(String rawPayload, ZenohChannel channel, String publishKeyBase) {
        String taskId = null;
        String pathUrl = null;
        try {
            JsonNode root = mapper.readTree(rawPayload == null || rawPayload.isBlank() ? "{}" : rawPayload);
            taskId = text(root, "task_id");
            pathUrl = text(root, "path_url");
            if (taskId == null || pathUrl == null) {
                return BodyReply.failure(400, "task_id 和 path_url 必填");
            }

            byte[] zip = buildInspectionZip(taskId);
            uploadToUrl(pathUrl, zip);

            log.info("质检结果文件上传成功: task_id={} url={} size={}bytes", taskId, pathUrl, zip.length);

            publishUploadedNotification(channel, publishKeyBase, taskId, "inspection_result.zip");
            return BodyReply.success("upload completed", null);
        } catch (Exception exception) {
            log.warn("质检结果文件上传失败: task_id={} msg={}", taskId, exception.getMessage());
            return BodyReply.failure(500, "upload failed: " + exception.getMessage());
        }
    }

    /** 生成仿真质检结果 ZIP（report.json + summary.txt）。 */
    byte[] buildInspectionZip(String taskId) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("task_id", taskId);
        report.put("format", "inspection_report_v2");
        report.put("schema_version", "1.0");
        report.put("overall_result", "pass");
        report.put("overall_message", "simulation inspection: all modules passed");
        report.put("modules", java.util.List.of(
                Map.of("module_id", "sim_chassis", "result", "pass", "message", "chassis ok"),
                Map.of("module_id", "sim_lidar", "result", "pass", "message", "lidar ok")));
        report.put("generated_at", java.time.Instant.now().toString());

        String reportJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        String summary = "Simulation Inspection Report\n"
                + "Task ID: " + taskId + "\n"
                + "Result: PASS\n"
                + "Generated: " + java.time.Instant.now() + "\n";

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("report.json"));
            zip.write(reportJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("summary.txt"));
            zip.write(summary.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    /** HTTP PUT 二进制到预签名 URL。 */
    void uploadToUrl(String url, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/zip")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SEC))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    /** zenoh pub task-result-files-uploaded 通知云端。 */
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
            channel.putOnce(key, mapper.writeValueAsString(root));
            log.info("task-result-files-uploaded 已通知: task_id={} key={}", taskId, key);
        } catch (Exception exception) {
            log.warn("task-result-files-uploaded 通知失败: task_id={} msg={}", taskId, exception.getMessage());
        }
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
