package com.agv.navdeployer.rms.map;

import com.agv.navdeployer.rms.protocol.dto.command.MapNameRequest;
import com.agv.navdeployer.rms.protocol.dto.command.MapReplyData;
import com.agv.navdeployer.rms.protocol.dto.command.RelocateRequest;
import com.agv.navdeployer.rms.protocol.dto.command.BodyReply;
import com.agv.navdeployer.service.MapModeTaskService;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Map;

public class RmsMapService {

    private static final Logger log = LoggerFactory.getLogger(RmsMapService.class);

    private static final String MODE_MAPPING = "MAPPING";
    private static final int MAX_DOWNLOAD_BYTES = 160 * 1024 * 1024;

    private static final long STALE_GRACE_MS = 5_000L;

    private final MapModeTaskService mapTaskService;
    private final SimAgvTelemetry telemetry;
    private final RmsMapState mapState;
    private final com.agv.navdeployer.metadata.service.MapMetaService mapMetaService;
    private final com.agv.navdeployer.metadata.exchange.MapPackageService mapPackageService;
    private final NativeMapClient nativeMaps;

    public RmsMapService(MapModeTaskService mapTaskService,
                         SimAgvTelemetry telemetry,
                         RmsMapState mapState,
                         com.agv.navdeployer.metadata.service.MapMetaService mapMetaService,
                         com.agv.navdeployer.metadata.exchange.MapPackageService mapPackageService,
                         NativeMapClient nativeMaps) {
        this.mapTaskService = mapTaskService;
        this.telemetry = telemetry;
        this.mapState = mapState;
        this.mapMetaService = mapMetaService;
        this.mapPackageService = mapPackageService;
        this.nativeMaps = nativeMaps;
    }

    public BodyReply startMapping(MapNameRequest request) {
        String mapName = request == null ? null : request.mapName();
        try {
            mapTaskService.startMapping();
            mapState.setPendingMapName(mapName);
            log.info("RMS start_mapping accepted, pendingMapName={}", mapName);
            return BodyReply.success("start mapping accepted", null);
        } catch (Exception exception) {
            return failure("start mapping failed", exception);
        }
    }

    public BodyReply stopMapping(MapNameRequest request) {
        if (cleanupStalePending()) {
            return BodyReply.failure(409,
                    "建图会话已不存在（机器人可能已重启或建图被终止），暂存地图名已清理，请重新 start_mapping");
        }
        String requested = request == null ? null : request.mapName();
        String mapName = requested != null ? requested : mapState.getPendingMapName();
        if (mapName == null) {
            return BodyReply.failure(400, "缺少地图名：stop_mapping 未携带且 start_mapping 未预置 map_name");
        }
        try {
            mapTaskService.saveMap(mapName);
            mapState.clearPendingMapName();
            log.info("RMS stop_mapping accepted, mapName={}", mapName);
            return BodyReply.success("save map accepted: " + mapName, null);
        } catch (Exception exception) {
            return failure("save map failed", exception);
        }
    }

    public BodyReply mappingStatus() {
        cleanupStalePending();
        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        String mode = status == null ? null : status.mode();
        boolean mapping = MODE_MAPPING.equals(mode);
        return new BodyReply(mapping ? 1 : 0,
                mapping ? "is mapping" : "no mapping",
                System.currentTimeMillis(),
                new MapReplyData.WorkModeData(mapping ? 1 : 0));
    }

    public BodyReply listMaps() {
        try {
            List<String> mapNames = mapTaskService.listRobotMaps();
            return BodyReply.success("ok", new MapReplyData.MapNamesData(mapNames));
        } catch (Exception exception) {
            return failure("list maps failed", exception);
        }
    }

    public BodyReply changeMap(MapNameRequest request) {
        String mapName = request == null ? null : request.mapName();
        if (mapName == null) {
            return BodyReply.failure(400, "缺少地图名：mapping/change 未携带 map_name");
        }
        try {
            var v2Map = mapMetaService.findByUploadName(mapName);
            if (v2Map == null || v2Map.getRobotMapName() == null) {
                return BodyReply.failure(404, "找不到可切换的机器人地图: " + mapName);
            }
            com.agv.navdeployer.vo.MapModeTaskVO task =
                    mapTaskService.switchNativeMap(v2Map.getMapName(), v2Map.getRobotMapName());
            BodyReply finalReply = awaitMapTaskTerminal(task.id(), 100_000L, "change map");
            if (finalReply.status() == 0 && v2Map != null && v2Map.getRobotMapName() != null
                    && !com.agv.navdeployer.metadata.entity.MapMeta.STATUS_PUBLISHED.equals(v2Map.getStatus())) {
                mapMetaService.publish(v2Map.getId(), "RMS");
            }
            log.info("RMS mapping/change 终态回包: map={} taskId={} status={} msg={}",
                    mapName, task.id(), finalReply.status(), finalReply.msg());
            return finalReply;
        } catch (Exception exception) {
            return failure("change map failed", exception);
        }
    }

    private BodyReply awaitMapTaskTerminal(Long taskId, long timeoutMs, String action)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            var current = mapTaskService.currentTask();
            if (current == null || current.id() == null) {
                continue;
            }
            if (!current.id().equals(taskId)) {
                return BodyReply.failure(409, action + " 任务被其他模式任务取代: task="
                        + current.id() + " (" + current.type() + ")");
            }
            switch (current.status()) {
                case "SUCCEEDED" -> {
                    return BodyReply.success(
                            action + " ok" + (current.robotMapName() == null ? "" : ": " + current.robotMapName()),
                            null);
                }
                case "FAILED" -> {
                    return BodyReply.failure(500, action + " failed: "
                            + (current.errorMessage() == null ? "未知原因" : current.errorMessage()));
                }
                default -> {
                }
            }
        }
        return BodyReply.failure(504, action + " timeout: 任务未在时限内到达终态（机器人仍在执行或结论未送达）");
    }

    public BodyReply getCurrentMap() {
        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        String mapName = status == null ? null : status.mapName();
        if (mapName == null || mapName.isBlank()) {
            return BodyReply.failure(404, "当前无激活地图");
        }
        return BodyReply.success("ok", new MapReplyData.MapNameData(mapName));
    }

    public BodyReply deleteMap(MapNameRequest request) {
        String mapName = request == null ? null : request.mapName();
        if (mapName == null) {
            return BodyReply.failure(400, "缺少地图名：mapping/delete 未携带 map_name");
        }
        try {
            var v2Map = mapMetaService.findByUploadName(mapName);
            if (v2Map == null) {
                return BodyReply.failure(404, "地图不存在: " + mapName);
            }
            try {
                mapMetaService.delete(v2Map.getId());
            } catch (IllegalStateException conflict) {
                return BodyReply.failure(400, conflict.getMessage());
            }
            log.info("RMS mapping/delete done, mapName={} mapId={}(v2)", mapName, v2Map.getId());
            return BodyReply.success("delete map accepted: " + mapName, null);
        } catch (Exception exception) {
            return failure("delete map failed", exception);
        }
    }

    public BodyReply relocate(RelocateRequest request) {
        if (request == null || request.incomplete()) {
            return BodyReply.failure(400, "重定位参数不完整：需要 x / y / theta");
        }
        try {
            com.agv.navdeployer.vo.MapModeTaskVO task =
                    mapTaskService.relocalize(null, request.x(), request.y(), request.theta());
            BodyReply finalReply = awaitMapTaskTerminal(task.id(), 120_000L, "relocate");
            log.info("RMS relocate 终态回包: taskId={} status={} msg={}",
                    task.id(), finalReply.status(), finalReply.msg());
            return finalReply;
        } catch (Exception exception) {
            return failure("relocate failed", exception);
        }
    }

    public BodyReply modeSet() {
        return BodyReply.failure(501, "仿真机器人不支持模式切换");
    }

    public BodyReply modeGet() {
        return BodyReply.success("auto mode", new MapReplyData.ModeData("auto"));
    }

    public BodyReply exportMap(String rawPayload,
                               com.fasterxml.jackson.databind.ObjectMapper mapper) {
        try {
            var root = mapper.readTree(rawPayload == null || rawPayload.isBlank() ? "{}" : rawPayload);
            String mapName = text(root, "map_name");
            String uploadUrl = text(root, "upload_url");
            if (mapName == null || uploadUrl == null) {
                return BodyReply.failure(400, "map_name 和 upload_url 必填");
            }

            var v2Map = mapMetaService.findByUploadName(mapName);
            if (v2Map == null) {
                return BodyReply.failure(404, "地图不存在: " + mapName);
            }
            byte[] nativePackage = nativeMaps.exportIfConfigured(v2Map.getMapName(), v2Map.getRobotMapName());
            byte[] zip = mapPackageService.exportZip(v2Map.getId(), nativePackage);
            if (nativePackage != null && zip.length > MAX_DOWNLOAD_BYTES) {
                return BodyReply.failure(413, "整机地图 ZIP 超过 160 MiB");
            }
            uploadZipToUrl(uploadUrl, v2Map.getMapName(), zip);
            log.info("RMS mapping/get 新格式上传成功: map={} size={}bytes native={}",
                    mapName, zip.length, nativePackage != null);
            return BodyReply.success("map exported: " + mapName,
                    Map.of("native_included", nativePackage != null));
        } catch (Exception exception) {
            log.warn("RMS mapping/get 失败: {}", exception.getMessage());
            return BodyReply.failure(500, "map export failed: " + exception.getMessage());
        }
    }

    private void uploadZipToUrl(String url, String mapName, byte[] body) throws Exception {
        String boundary = "----NavDeployerBoundary" + System.currentTimeMillis();
        String prefix = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"map_name\"\r\n\r\n"
                + mapName + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + mapName + ".zip\"\r\n"
                + "Content-Type: application/zip\r\n\r\n";
        var multipart = java.net.http.HttpRequest.BodyPublishers.concat(
                java.net.http.HttpRequest.BodyPublishers.ofString(prefix),
                java.net.http.HttpRequest.BodyPublishers.ofByteArray(body),
                java.net.http.HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));

        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipart)
                .timeout(java.time.Duration.ofSeconds(nativeMaps.enabled() ? 90 : 30))
                .build();
        var response = java.net.http.HttpClient.newHttpClient().send(
                request, java.net.http.HttpResponse.BodyHandlers.ofString());
        log.info("mapping/get 上传响应: status={}\nbody={}", response.statusCode(), response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    public BodyReply downloadMap(String payload, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        String mapName;
        URI uri;
        try {
            var request = mapper.readTree(payload);
            if (request == null || !request.path("map_name").isTextual()
                    || !request.path("map_url").isTextual()) {
                return BodyReply.failure(400, "map_name 和 map_url 必须为字符串");
            }
            mapName = text(request, "map_name");
            String mapUrl = text(request, "map_url");
            if (mapName == null || mapUrl == null || mapName.length() > 512 || mapUrl.length() > 512) {
                return BodyReply.failure(400, "map_name 和 map_url 必填且最长 512 字符");
            }
            uri = URI.create(mapUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                return BodyReply.failure(400, "map_url 必须是有效的 HTTP(S) 下载地址");
            }
        } catch (IOException | IllegalArgumentException exception) {
            return BodyReply.failure(400, "地图下发请求格式无效");
        }

        byte[] zip;
        try {
            zip = fetchMapZip(uri);
        } catch (IOException exception) {
            log.warn("RMS mapping/download 下载失败: map={}, errorType={}",
                    mapName, exception.getClass().getSimpleName());
            return BodyReply.failure(502, "地图 ZIP 下载失败，请检查地址、有效期和包大小");
        }
        try {
            var imported = mapPackageService.importZip(zip, mapName, "RMS", nativeMaps::installMap);
            boolean installed = imported.robotMapName() != null;
            return BodyReply.success(installed ? "map imported and installed" : "map imported on host", Map.of(
                    "map_id", imported.id(),
                    "map_name", mapName,
                    "robot_installed", installed));
        } catch (NativeMapClient.TransferException exception) {
            return BodyReply.failure(exception.conflict() ? 409 : 502, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return BodyReply.failure(422, exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("RMS mapping/download 导入失败: map={}", mapName, exception);
            return BodyReply.failure(500, "地图导入失败，请检查包格式或同名地图");
        }
    }

    private static byte[] fetchMapZip(URI uri) throws IOException {
        var connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(15_000);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK
                    || connection.getContentLengthLong() > MAX_DOWNLOAD_BYTES) {
                throw new IOException("地图下载响应无效或超过大小限制");
            }
            try (var input = connection.getInputStream()) {
                byte[] zip = input.readNBytes(MAX_DOWNLOAD_BYTES + 1);
                if (zip.length == 0 || zip.length > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("地图 ZIP 大小无效");
                }
                return zip;
            }
        } finally {
            connection.disconnect();
        }
    }

    private String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BodyReply failure(String action, Throwable exception) {
        log.warn("RMS {} failed: {}", action, exception.getMessage());
        return BodyReply.failure(500, action + ": " + exception.getMessage());
    }

    private boolean cleanupStalePending() {
        String pending = mapState.getPendingMapName();
        if (pending == null) {
            return false;
        }
        long since = mapState.getPendingSinceEpochMs();
        if (since > 0 && System.currentTimeMillis() - since < STALE_GRACE_MS) {
            return false;
        }
        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        String mode = status == null ? null : status.mode();
        if (mode == null || MODE_MAPPING.equals(mode)) {
            return false;
        }
        mapState.clearPendingMapName();
        log.info("暂存地图名已清理（建图会话不存在）: name={} currentMode={} pendingSince={}",
                pending, mode, since);
        return true;
    }
}
