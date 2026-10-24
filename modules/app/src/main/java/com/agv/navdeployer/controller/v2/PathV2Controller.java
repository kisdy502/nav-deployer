package com.agv.navdeployer.controller.v2;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.metadata.dto.PathSaveDTO;
import com.agv.navdeployer.metadata.service.MapPathService;
import com.agv.navdeployer.metadata.vo.PathV2VO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

import java.util.List;

@Tag(name = "路线管理 v2", description = "途经点位链 + 段（段级限速/曲线贝塞尔/倒车，旧曲线能力已合并）")
@RestController
@RequestMapping("/api/v2/map-paths")
public class PathV2Controller {

    private final MapPathService pathService;

    public PathV2Controller(MapPathService pathService) {
        this.pathService = pathService;
    }

    @Operation(summary = "路线列表", description = "mapId 或 floorId 至少一个")
    @GetMapping
    public ApiResponse<List<PathV2VO>> list(@RequestParam(required = false) Long mapId,
                                            @RequestParam(required = false) Long floorId) {
        if (floorId != null) {
            return ApiResponse.ok(pathService.listByFloor(floorId));
        }
        if (mapId != null) {
            return ApiResponse.ok(pathService.list(mapId));
        }
        throw new IllegalArgumentException("mapId 与 floorId 至少传一个");
    }

    @Operation(summary = "新建路线", description = "waypointIds 为有序途经链（≥2）；segments 缺省自动生成直线段")
    @PostMapping
    public ResponseEntity<ApiResponse<PathV2VO>> create(@Valid @RequestBody PathSaveDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(pathService.create(dto)));
    }

    @Operation(summary = "路线详情（含段）")
    @GetMapping("/{id}")
    public ApiResponse<PathV2VO> get(@PathVariable Long id) {
        return ApiResponse.ok(pathService.get(id));
    }

    @Operation(summary = "更新路线", description = "携带 waypointIds 时整链替换并重建段；仅改属性时不重建")
    @PutMapping("/{id}")
    public ApiResponse<PathV2VO> update(@PathVariable Long id, @Valid @RequestBody PathSaveDTO dto) {
        return ApiResponse.ok(pathService.update(id, dto));
    }

    @Operation(summary = "删除路线")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        pathService.delete(id);
        return ApiResponse.ok();
    }
}
