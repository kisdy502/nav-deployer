package com.agv.navdeployer.controller.v2;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.metadata.dto.MapCreateFromLiveDTO;
import com.agv.navdeployer.metadata.dto.MapMigrateDTO;
import com.agv.navdeployer.metadata.dto.MapPublishDTO;
import com.agv.navdeployer.metadata.dto.MapUpdateDTO;
import com.agv.navdeployer.metadata.exchange.MapPackageService;
import com.agv.navdeployer.metadata.service.MapMetaService;
import com.agv.navdeployer.metadata.vo.MapFullVO;
import com.agv.navdeployer.metadata.vo.MapV2VO;
import com.agv.navdeployer.vo.MapGridVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** v2 多楼层地图管理：建图、全量树、发布、新格式 zip 包导入导出、旧版迁移。 */
@Tag(name = "地图管理 v2", description = "多楼层地图元数据（新格式：楼层/点位/区域/货架/路线/楼层间连接）")
@RestController
@RequestMapping("/api/v2/maps")
public class MapV2Controller {

    private final MapMetaService mapMetaService;
    private final MapPackageService mapPackageService;
    private final ObjectMapper objectMapper;

    public MapV2Controller(MapMetaService mapMetaService,
                           MapPackageService mapPackageService,
                           ObjectMapper objectMapper) {
        this.mapMetaService = mapMetaService;
        this.mapPackageService = mapPackageService;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "地图列表")
    @GetMapping
    public ApiResponse<List<MapV2VO>> list() {
        return ApiResponse.ok(mapMetaService.list());
    }

    @Operation(summary = "从实时快照建图", description = "保存当前 /map 栅格为新地图，自动创建默认楼层 F1")
    @PostMapping("/from-live")
    public ResponseEntity<ApiResponse<MapV2VO>> createFromLive(@Valid @RequestBody MapCreateFromLiveDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(mapMetaService.createFromLive(dto)));
    }

    @Operation(summary = "导入新格式地图包", description = "zip 含 map-info.json + global.yaml/pgm（png 可选），全量还原多楼层元数据")
    @PostMapping(value = "/import-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MapV2VO>> importZip(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "map_name", required = false) String mapName,
            @RequestParam(value = "modified_by", required = false) String modifiedBy) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(mapPackageService.importZip(file.getBytes(), mapName, modifiedBy)));
    }

    @Operation(summary = "旧版地图迁移", description = "nav_map → v2 多楼层格式；点位类型映射、路线曲线字段完整保留")
    @PostMapping("/migrate/{navMapId}")
    public ResponseEntity<ApiResponse<MapV2VO>> migrate(@PathVariable Long navMapId,
                                                        @RequestBody(required = false) MapMigrateDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(mapMetaService.migrateFromNavMap(navMapId, dto)));
    }

    @Operation(summary = "地图详情")
    @GetMapping("/{id}")
    public ApiResponse<MapV2VO> get(@PathVariable Long id) {
        return ApiResponse.ok(mapMetaService.get(id));
    }

    @Operation(summary = "地图全量树", description = "一次拉取：地图 + 各楼层（点位/区域/货架/路线）+ 楼层间连接（编辑器初始化用）")
    @GetMapping("/{id}/full")
    public ApiResponse<MapFullVO> getFull(@PathVariable Long id) {
        return ApiResponse.ok(mapMetaService.loadFull(id));
    }

    @Operation(summary = "地图栅格数据", description = "保存的 OccupancyGrid JSON（application/json，前端画布渲染）")
    @GetMapping("/{id}/grid")
    public ResponseEntity<byte[]> grid(@PathVariable Long id) throws Exception {
        MapGridVO grid = mapMetaService.getGrid(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsBytes(grid));
    }

    @Operation(summary = "更新地图元信息", description = "重命名 / 机器人地图名（版本 +1）")
    @PutMapping("/{id}")
    public ApiResponse<MapV2VO> update(@PathVariable Long id, @Valid @RequestBody MapUpdateDTO dto) {
        return ApiResponse.ok(mapMetaService.update(id, dto));
    }

    @Operation(summary = "发布地图", description = "设为使用中（全局唯一 PUBLISHED，其余归档），版本 +1")
    @PostMapping("/{id}/publish")
    public ApiResponse<MapV2VO> publish(@PathVariable Long id,
                                        @RequestBody(required = false) MapPublishDTO dto) {
        return ApiResponse.ok(mapMetaService.publish(id, dto == null ? null : dto.modifiedBy()));
    }

    @Operation(summary = "替换底图为实时栅格", description = "用当前 /map 栅格替换地图底图，元数据保持不变，版本 +1")
    @PostMapping("/{id}/grid-from-live")
    public ApiResponse<MapV2VO> replaceGridFromLive(@PathVariable Long id,
                                                    @RequestBody(required = false) MapPublishDTO dto) {
        return ApiResponse.ok(mapMetaService.replaceGridFromLive(id, dto == null ? null : dto.modifiedBy()));
    }

    @Operation(summary = "导出新格式地图包", description = "map-info.json + global.yaml/pgm/png 四件套 zip（zenoh 上传 RMS 同格式）")
    @GetMapping("/{id}/package")
    public ResponseEntity<byte[]> exportPackage(@PathVariable Long id) {
        MapV2VO map = mapMetaService.get(id);
        byte[] zip = mapPackageService.exportZip(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + map.mapName() + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(zip);
    }

    @Operation(summary = "删除地图", description = "PUBLISHED 状态拒删；楼层/点位/区域/货架/路线/连接级联删除")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        mapMetaService.delete(id);
        return ApiResponse.ok();
    }
}
