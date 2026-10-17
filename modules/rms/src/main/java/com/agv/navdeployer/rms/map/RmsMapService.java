package com.agv.navdeployer.rms.map;

import com.agv.navdeployer.entity.NavMap;
import com.agv.navdeployer.mapper.NavMapMapper;
import com.agv.navdeployer.rms.protocol.dto.command.MapNameRequest;
import com.agv.navdeployer.rms.protocol.dto.command.MapReplyData;
import com.agv.navdeployer.rms.protocol.dto.command.RelocateRequest;
import com.agv.navdeployer.rms.protocol.dto.command.BodyReply;
import com.agv.navdeployer.service.MapModeTaskService;
import com.agv.navdeployer.service.NavMapService;
import com.agv.navdeployer.sim.RosCommandDispatcher;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * RMS 地图类指令的业务编排：协议适配（zenoh 命令 ↔ 业务 bean）+ 复用既有服务。
 *
 * <p>分层约定（对齐 team-ai-rules）：本类只做校验、编排与回复组装；
 * 建图启停复用 {@link MapModeTaskService}，地图库管理（DB + MinIO）复用
 * {@link NavMapService}，机器人状态取自 {@link SimAgvTelemetry}，不重复实现任何业务逻辑。
 *
 * <p>回复语义对齐接口文档：status=0 成功；失败时 msg 必填。建图/存图的远端完成度
 * 由云平台轮询 mapping/status 获取（建图完成通知为商服接口，不实现）。
 */
public class RmsMapService {

    private static final Logger log = LoggerFactory.getLogger(RmsMapService.class);

    private static final String MODE_MAPPING = "MAPPING";

    /** start_mapping 后 telemetry 镜像刷到 MAPPING 的滞后宽限（镜像 1Hz，取 5s 余量）。 */
    private static final long STALE_GRACE_MS = 5_000L;

    private final MapModeTaskService mapTaskService;
    private final NavMapService navMapService;
    private final NavMapMapper navMapMapper;
    private final RosCommandDispatcher dispatcher;
    private final SimAgvTelemetry telemetry;
    private final RmsMapState mapState;

    public RmsMapService(MapModeTaskService mapTaskService,
                         NavMapService navMapService,
                         NavMapMapper navMapMapper,
                         RosCommandDispatcher dispatcher,
                         SimAgvTelemetry telemetry,
                         RmsMapState mapState) {
        this.mapTaskService = mapTaskService;
        this.navMapService = navMapService;
        this.navMapMapper = navMapMapper;
        this.dispatcher = dispatcher;
        this.telemetry = telemetry;
        this.mapState = mapState;
    }

    /** start_mapping：map_name 可选（停止时的默认保存名）。 */
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

    /** stop_mapping：map_name 缺省时使用 start_mapping 预置名。 */
    public BodyReply stopMapping(MapNameRequest request) {
        // 建图会话已不存在的善后：机器人重启/建图被外部终止，暂存名已无意义
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

    /** mapping/status：status 0=没有建图 1=正在建图；work_mode 0=定位 1=建图。 */
    public BodyReply mappingStatus() {
        // RMS 轮询建图状态的时机顺带做机器人重启善后（清掉已死会话的暂存名）
        cleanupStalePending();
        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        String mode = status == null ? null : status.mode();
        boolean mapping = MODE_MAPPING.equals(mode);
        return new BodyReply(mapping ? 1 : 0,
                mapping ? "is mapping" : "no mapping",
                System.currentTimeMillis(),
                new MapReplyData.WorkModeData(mapping ? 1 : 0));
    }

    /** mapping/list：本体侧地图名列表。 */
    public BodyReply listMaps() {
        try {
            List<String> mapNames = mapTaskService.listRobotMaps();
            return BodyReply.success("ok", new MapReplyData.MapNamesData(mapNames));
        } catch (Exception exception) {
            return failure("list maps failed", exception);
        }
    }

    /** mapping/change：按名切换（优先 robot_map_name，其次 map_name）。 */
    public BodyReply changeMap(MapNameRequest request) {
        String mapName = request == null ? null : request.mapName();
        if (mapName == null) {
            return BodyReply.failure(400, "缺少地图名：mapping/change 未携带 map_name");
        }
        try {
            NavMap map = findMapByName(mapName);
            if (map == null) {
                return BodyReply.failure(404, "地图不存在: " + mapName);
            }
            mapTaskService.switchMap(map.getId(), null);
            log.info("RMS mapping/change accepted, mapName={} navMapId={}", mapName, map.getId());
            return BodyReply.success("change map accepted: " + mapName, null);
        } catch (Exception exception) {
            return failure("change map failed", exception);
        }
    }

    /** mapping/get_current：优先机器人实时上报，退回库内 ACTIVE 记录。 */
    public BodyReply getCurrentMap() {
        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        String mapName = status == null ? null : status.mapName();
        if (mapName == null || mapName.isBlank()) {
            try {
                NavMap active = navMapService.requireActiveMap();
                mapName = active.getRobotMapName() != null ? active.getRobotMapName() : active.getMapName();
            } catch (Exception ignored) {
                mapName = null;
            }
        }
        if (mapName == null || mapName.isBlank()) {
            return BodyReply.failure(404, "当前无激活地图");
        }
        return BodyReply.success("ok", new MapReplyData.MapNameData(mapName));
    }

    /** mapping/delete：只删上位机侧（DB + MinIO），不动机器人磁盘；当前使用中的地图拒删。 */
    public BodyReply deleteMap(MapNameRequest request) {
        String mapName = request == null ? null : request.mapName();
        if (mapName == null) {
            return BodyReply.failure(400, "缺少地图名：mapping/delete 未携带 map_name");
        }
        try {
            if (isActiveMap(mapName)) {
                return BodyReply.failure(400, "当前使用中的地图不可删除: " + mapName);
            }
            NavMap map = findMapByName(mapName);
            if (map == null) {
                return BodyReply.failure(404, "地图不存在: " + mapName);
            }
            navMapService.delete(map.getId());
            log.info("RMS mapping/delete done, mapName={} navMapId={}", mapName, map.getId());
            return BodyReply.success("delete map accepted: " + mapName, null);
        } catch (Exception exception) {
            return failure("delete map failed", exception);
        }
    }

    /** agv/relocate：以当前地图为参照按指定坐标重定位。 */
    public BodyReply relocate(RelocateRequest request) {
        if (request == null || request.incomplete()) {
            return BodyReply.failure(400, "重定位参数不完整：需要 x / y / theta");
        }
        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        String mapName = status == null ? null : status.mapName();
        if (mapName == null || mapName.isBlank()) {
            return BodyReply.failure(409, "当前无激活地图，无法重定位");
        }
        try {
            RosCommandDispatcher.RelocalizeResult result =
                    dispatcher.callRelocalize(mapName, request.x(), request.y(), request.theta()).join();
            if (!result.success()) {
                return BodyReply.failure(500, "机器人拒绝重定位: " + result.message());
            }
            log.info("RMS relocate accepted, map={} x={} y={} theta={}",
                    mapName, request.x(), request.y(), request.theta());
            return BodyReply.success("relocate accepted", null);
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            return failure("relocate failed", cause);
        } catch (Exception exception) {
            return failure("relocate failed", exception);
        }
    }

    /** mode/set：仿真机器人不支持模式切换，固定回 501。 */
    public BodyReply modeSet() {
        return BodyReply.failure(501, "仿真机器人不支持模式切换");
    }

    /** mode/get：固定自动模式。 */
    public BodyReply modeGet() {
        return BodyReply.success("auto mode", new MapReplyData.ModeData("auto"));
    }

    /**
     * mapping/get：从本体导出地图（云端给 upload_url，机器人打包地图上传）。
     * payload: {"map_name": "xxx", "upload_url": "https://...", "method": "POST"}
     */
    public BodyReply exportMap(String rawPayload,
                               com.fasterxml.jackson.databind.ObjectMapper mapper,
                               com.agv.navdeployer.exchange.ScheduleMapAdapter adapter,
                               com.agv.navdeployer.service.NavPointService navPointService,
                               com.agv.navdeployer.service.NavPathService navPathService) {
        try {
            var root = mapper.readTree(rawPayload == null || rawPayload.isBlank() ? "{}" : rawPayload);
            String mapName = text(root, "map_name");
            String uploadUrl = text(root, "upload_url");
            if (mapName == null || uploadUrl == null) {
                return BodyReply.failure(400, "map_name 和 upload_url 必填");
            }

            NavMap map = findMapByName(mapName);
            if (map == null) {
                return BodyReply.failure(404, "地图不存在: " + mapName);
            }

            // 从 DB 取栅格数据，用 ScheduleMapAdapter 打包
            var grid = mapper.readValue(navMapService.getGridData(map.getId()).json(),
                    com.agv.navdeployer.vo.MapGridVO.class);
            var points = navPointService.list(map.getId());
            var paths = navPathService.list(map.getId());
            var edgesByPath = new java.util.LinkedHashMap<Long, java.util.List<com.agv.navdeployer.vo.PathEdgeVO>>();
            for (var path : paths) {
                edgesByPath.put(path.getId(), navPathService.loadEdgeVOs(path.getId()));
            }
            byte[] zip = adapter.exportZip(map.getMapName(), grid, points, paths, edgesByPath);

            // HTTP POST zip 到云端提供的 URL（multipart: map_name + file）
            uploadZipToUrl(uploadUrl, map.getMapName(), zip);

            log.info("RMS mapping/get 上传成功: map={} url={} size={}bytes", mapName, uploadUrl, zip.length);
            return BodyReply.success("map exported: " + mapName, null);
        } catch (Exception exception) {
            log.warn("RMS mapping/get 失败: {}", exception.getMessage());
            return BodyReply.failure(500, "map export failed: " + exception.getMessage());
        }
    }

    private void uploadZipToUrl(String url, String mapName, byte[] body) throws Exception {
        String boundary = "----NavDeployerBoundary" + System.currentTimeMillis();
        var output = new java.io.ByteArrayOutputStream();
        // 表单字段 1：map_name（服务端 import-zip 要求，curl -F "map_name=xxx" 等价）
        output.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"map_name\"\r\n\r\n"
                + mapName + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // 表单字段 2：file（zip 二进制，curl -F "file=@test.zip" 等价）
        output.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + mapName + ".zip\"\r\n"
                + "Content-Type: application/zip\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.write(body);
        output.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(output.toByteArray()))
                .timeout(java.time.Duration.ofSeconds(30))
                .build();
        var response = java.net.http.HttpClient.newHttpClient().send(
                request, java.net.http.HttpResponse.BodyHandlers.ofString());
        log.info("mapping/get 上传响应: status={}\nbody={}", response.statusCode(), response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 优先 robot_map_name 精确匹配，其次 map_name。 */
    private NavMap findMapByName(String mapName) {
        NavMap byRobotName = navMapMapper.selectOne(Wrappers.lambdaQuery(NavMap.class)
                .eq(NavMap::getRobotMapName, mapName)
                .last("LIMIT 1"));
        if (byRobotName != null) {
            return byRobotName;
        }
        return navMapMapper.selectOne(Wrappers.lambdaQuery(NavMap.class)
                .eq(NavMap::getMapName, mapName)
                .last("LIMIT 1"));
    }

    private boolean isActiveMap(String mapName) {
        try {
            NavMap active = navMapService.requireActiveMap();
            return active != null && (mapName.equals(active.getRobotMapName())
                    || mapName.equals(active.getMapName()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private BodyReply failure(String action, Throwable exception) {
        log.warn("RMS {} failed: {}", action, exception.getMessage());
        return BodyReply.failure(500, action + ": " + exception.getMessage());
    }

    /**
     * 机器人重启/建图会话死亡的善后：暂存名存在、telemetry 确认已不在建图、
     * 且超过启动镜像滞后宽限期 → 清理暂存名。
     *
     * <p>宽限期防误杀：start_mapping 刚成功时 telemetry 镜像（1Hz）可能还没刷到
     * MAPPING，此刻 mode==NAVIGATION 不代表会话死了。bridge 侧 mode 在服务响应
     * 前已同步切换，镜像滞后最多 1~2s，取 5s 余量。
     *
     * <p>telemetry 不新鲜（机器人离线/rosbridge 断链）时不动：无法确认，留给下次。
     *
     * @return true = 本次调用执行了清理（调用方可据此改写回复语义）
     */
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
