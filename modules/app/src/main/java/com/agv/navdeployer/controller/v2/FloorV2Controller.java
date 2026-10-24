package com.agv.navdeployer.controller.v2;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.metadata.dto.FloorCreateDTO;
import com.agv.navdeployer.metadata.dto.FloorUpdateDTO;
import com.agv.navdeployer.metadata.service.MapFloorService;
import com.agv.navdeployer.metadata.vo.FloorVO;
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

@Tag(name = "楼层管理 v2", description = "多楼层地图的楼层增删改查")
@RestController
@RequestMapping("/api/v2/map-floors")
public class FloorV2Controller {

    private final MapFloorService floorService;

    public FloorV2Controller(MapFloorService floorService) {
        this.floorService = floorService;
    }

    @Operation(summary = "楼层列表", description = "map_id 必填，按 sort_order 排序")
    @GetMapping
    public ApiResponse<List<FloorVO>> list(@RequestParam Long mapId) {
        return ApiResponse.ok(floorService.list(mapId));
    }

    @Operation(summary = "新建楼层")
    @PostMapping
    public ResponseEntity<ApiResponse<FloorVO>> create(@Valid @RequestBody FloorCreateDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(floorService.create(dto)));
    }

    @Operation(summary = "楼层详情")
    @GetMapping("/{id}")
    public ApiResponse<FloorVO> get(@PathVariable Long id) {
        return ApiResponse.ok(floorService.get(id));
    }

    @Operation(summary = "更新楼层")
    @PutMapping("/{id}")
    public ApiResponse<FloorVO> update(@PathVariable Long id, @Valid @RequestBody FloorUpdateDTO dto) {
        return ApiResponse.ok(floorService.update(id, dto));
    }

    @Operation(summary = "删除楼层", description = "楼层下仍有点位/路线时拒绝；区域/货架级联删除")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        floorService.delete(id);
        return ApiResponse.ok();
    }
}
