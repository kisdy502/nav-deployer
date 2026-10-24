package com.agv.navdeployer.controller.v2;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.metadata.dto.ZoneCreateDTO;
import com.agv.navdeployer.metadata.dto.ZoneUpdateDTO;
import com.agv.navdeployer.metadata.service.MapZoneService;
import com.agv.navdeployer.metadata.vo.ZoneVO;
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

@Tag(name = "区域管理 v2", description = "区域约束：forbidden 禁行 / slowDown 减速 / ramp 坡道")
@RestController
@RequestMapping("/api/v2/map-zones")
public class ZoneV2Controller {

    private final MapZoneService zoneService;

    public ZoneV2Controller(MapZoneService zoneService) {
        this.zoneService = zoneService;
    }

    @Operation(summary = "区域列表", description = "mapId 或 floorId 至少一个")
    @GetMapping
    public ApiResponse<List<ZoneVO>> list(@RequestParam(required = false) Long mapId,
                                          @RequestParam(required = false) Long floorId) {
        if (floorId != null) {
            return ApiResponse.ok(zoneService.listByFloor(floorId));
        }
        if (mapId != null) {
            return ApiResponse.ok(zoneService.list(mapId));
        }
        throw new IllegalArgumentException("mapId 与 floorId 至少传一个");
    }

    @Operation(summary = "新建区域")
    @PostMapping
    public ResponseEntity<ApiResponse<ZoneVO>> create(@Valid @RequestBody ZoneCreateDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(zoneService.create(dto)));
    }

    @Operation(summary = "区域详情")
    @GetMapping("/{id}")
    public ApiResponse<ZoneVO> get(@PathVariable Long id) {
        return ApiResponse.ok(zoneService.get(id));
    }

    @Operation(summary = "更新区域")
    @PutMapping("/{id}")
    public ApiResponse<ZoneVO> update(@PathVariable Long id, @Valid @RequestBody ZoneUpdateDTO dto) {
        return ApiResponse.ok(zoneService.update(id, dto));
    }

    @Operation(summary = "删除区域")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        zoneService.delete(id);
        return ApiResponse.ok();
    }
}
