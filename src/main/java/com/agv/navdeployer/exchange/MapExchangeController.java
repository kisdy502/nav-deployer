package com.agv.navdeployer.exchange;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.dto.NavPathCreateDTO;
import com.agv.navdeployer.dto.NavPathEdgesUpdateDTO;
import com.agv.navdeployer.dto.NavPointCreateDTO;
import com.agv.navdeployer.dto.PathEdgeDTO;
import com.agv.navdeployer.service.NavMapService;
import com.agv.navdeployer.service.NavPathService;
import com.agv.navdeployer.service.NavPointService;
import com.agv.navdeployer.sim.LiveMapCache;
import com.agv.navdeployer.vo.MapGridVO;
import com.agv.navdeployer.vo.NavMapVO;
import com.agv.navdeployer.vo.NavPathVO;
import com.agv.navdeployer.vo.NavPointVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调度系统地图交换（蓝心底盘 zip 格式，见 {@link ScheduleMapAdapter}）。
 *
 * <p>导出：把当前地图（栅格+点位+路线）打成调度可用的 zip，供上层调度拉取/上传；
 * 导入：接收调度下发的其他机器人地图 zip，栅格入库为草稿 + 点位/路线还原。
 */
@Tag(name = "调度地图交换", description = "蓝心底盘 zip 格式导出/导入")
@RestController
@RequestMapping("/api/v1")
public class MapExchangeController {

    private final ScheduleMapAdapter adapter;
    private final NavMapService navMapService;
    private final NavPointService navPointService;
    private final NavPathService navPathService;
    private final ObjectMapper objectMapper;

    public MapExchangeController(ScheduleMapAdapter adapter,
                                 NavMapService navMapService,
                                 NavPointService navPointService,
                                 NavPathService navPathService,
                                 ObjectMapper objectMapper) {
        this.adapter = adapter;
        this.navMapService = navMapService;
        this.navPointService = navPointService;
        this.navPathService = navPathService;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "导出调度 zip", description = "把地图（pgm/png/yaml/lxmap/feature/schema 六件套）打成蓝心底盘格式的 zip")
    @GetMapping("/nav-maps/{id}/schedule-zip")
    public ResponseEntity<byte[]> exportScheduleZip(@PathVariable Long id) throws Exception {
        NavMapVO map = navMapService.get(id);
        MapGridVO grid = objectMapper.readValue(navMapService.getGridData(id).json(), MapGridVO.class);
        List<NavPointVO> points = navPointService.list(id);
        List<NavPathVO> paths = navPathService.list(id);
        Map<Long, List<com.agv.navdeployer.vo.PathEdgeVO>> edgesByPath = new LinkedHashMap<>();
        for (NavPathVO path : paths) {
            edgesByPath.put(path.getId(), navPathService.loadEdgeVOs(path.getId()));
        }
        byte[] zip = adapter.exportZip(map.getMapName(), grid, points, paths, edgesByPath);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + map.getMapName() + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(zip);
    }

    @Operation(summary = "导入调度 zip", description = "解析调度下发的地图 zip：栅格入库（草稿）+ 点位 + 路线（每条 Route 一条单边路线）")
    @PostMapping(value = "/nav-maps/schedule-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> importScheduleZip(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "map_name", required = false) String mapName) throws Exception {
        ScheduleMapAdapter.ScheduleMapImport parsed = adapter.importZip(file.getBytes());

        // 栅格入库（草稿，复用机器人地图同步的存储链路：gzip JSON → MinIO）
        String name = (mapName == null || mapName.isBlank())
                ? "schedule_" + System.currentTimeMillis() : mapName.trim();
        LiveMapCache.OccupancyGrid grid = new LiveMapCache.OccupancyGrid(
                "map", parsed.resolution(), parsed.width(), parsed.height(),
                parsed.originX(), parsed.originY(), 0.0, parsed.data(), java.time.Instant.now());
        NavMapVO saved = navMapService.saveRobotGrid(name, name, grid);

        // 点位还原（point_type 缺省 NORMAL；编码冲突时跳过该点）
        Map<String, Long> codeToId = new LinkedHashMap<>();
        int pointFail = 0;
        for (ScheduleMapAdapter.ImportedPoint point : parsed.points()) {
            try {
                NavPointVO created = navPointService.create(new NavPointCreateDTO(
                        saved.getId(), point.code(),
                        point.pointType() == null ? "NORMAL" : point.pointType(),
                        point.x(), point.y(), point.yaw(), "调度zip导入"));
                codeToId.put(point.code(), created.getId());
            } catch (Exception e) {
                pointFail++;
            }
        }

        // 路线还原：每条 Route 一条单边路线（调度拓扑是无路径概念的直连图）
        int pathOk = 0;
        int pathFail = 0;
        int pathSeq = 1;
        for (ScheduleMapAdapter.ImportedRoute route : parsed.routes()) {
            Long sourceId = codeToId.get(route.sourceCode());
            Long targetId = codeToId.get(route.targetCode());
            if (sourceId == null || targetId == null) {
                pathFail++;
                continue;
            }
            try {
                NavPathVO path = navPathService.create(new NavPathCreateDTO(
                        saved.getId(), "SCH" + pathSeq, "调度导入-" + route.sourceCode() + "-" + route.targetCode()));
                navPathService.replaceEdges(path.getId(), new NavPathEdgesUpdateDTO(List.of(new PathEdgeDTO(
                        sourceId, targetId, "STRAIGHT", List.of(),
                        route.maxSpeed(), false, route.reverse()))));
                pathOk++;
            } catch (Exception e) {
                pathFail++;
            }
            pathSeq++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("navMapId", saved.getId());
        result.put("mapName", saved.getMapName());
        result.put("points", codeToId.size());
        result.put("pointSkipped", pointFail);
        result.put("routes", pathOk);
        result.put("routeSkipped", pathFail);
        return ApiResponse.ok(result);
    }
}
