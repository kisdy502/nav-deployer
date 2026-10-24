package com.agv.navdeployer.controller.v2;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.metadata.dto.WaypointCreateDTO;
import com.agv.navdeployer.metadata.dto.WaypointUpdateDTO;
import com.agv.navdeployer.metadata.service.MapWaypointService;
import com.agv.navdeployer.metadata.vo.WaypointVO;
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

@Tag(name = "点位管理 v2", description = "点位类型：normal/charge/pickup/dropoff/storage/elevator/stairs/ramp")
@RestController
@RequestMapping("/api/v2/map-waypoints")
public class WaypointV2Controller {

    private final MapWaypointService waypointService;

    public WaypointV2Controller(MapWaypointService waypointService) {
        this.waypointService = waypointService;
    }

    @Operation(summary = "点位列表", description = "mapId 或 floorId 至少一个")
    @GetMapping
    public ApiResponse<List<WaypointVO>> list(@RequestParam(required = false) Long mapId,
                                              @RequestParam(required = false) Long floorId) {
        if (floorId != null) {
            return ApiResponse.ok(waypointService.listByFloor(floorId));
        }
        if (mapId != null) {
            return ApiResponse.ok(waypointService.list(mapId));
        }
        throw new IllegalArgumentException("mapId 与 floorId 至少传一个");
    }

    @Operation(summary = "新建点位")
    @PostMapping
    public ResponseEntity<ApiResponse<WaypointVO>> create(@Valid @RequestBody WaypointCreateDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(waypointService.create(dto)));
    }

    @Operation(summary = "点位详情")
    @GetMapping("/{id}")
    public ApiResponse<WaypointVO> get(@PathVariable Long id) {
        return ApiResponse.ok(waypointService.get(id));
    }

    @Operation(summary = "更新点位")
    @PutMapping("/{id}")
    public ApiResponse<WaypointVO> update(@PathVariable Long id, @Valid @RequestBody WaypointUpdateDTO dto) {
        return ApiResponse.ok(waypointService.update(id, dto));
    }

    @Operation(summary = "删除点位", description = "被路线或楼层间连接引用时拒绝")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        waypointService.delete(id);
        return ApiResponse.ok();
    }
}
