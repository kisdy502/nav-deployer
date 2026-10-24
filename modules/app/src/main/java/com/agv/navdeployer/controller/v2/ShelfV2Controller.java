package com.agv.navdeployer.controller.v2;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.metadata.dto.ShelfSaveDTO;
import com.agv.navdeployer.metadata.service.MapShelfService;
import com.agv.navdeployer.metadata.vo.ShelfVO;
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

@Tag(name = "货架管理 v2", description = "货架整树保存：footprint + 层 + 格位（含商品信息）")
@RestController
@RequestMapping("/api/v2/map-shelves")
public class ShelfV2Controller {

    private final MapShelfService shelfService;

    public ShelfV2Controller(MapShelfService shelfService) {
        this.shelfService = shelfService;
    }

    @Operation(summary = "货架列表（整树）", description = "mapId 或 floorId 至少一个，含层与格位")
    @GetMapping
    public ApiResponse<List<ShelfVO>> list(@RequestParam(required = false) Long mapId,
                                           @RequestParam(required = false) Long floorId) {
        if (floorId != null) {
            return ApiResponse.ok(shelfService.listByFloor(floorId));
        }
        if (mapId != null) {
            return ApiResponse.ok(shelfService.list(mapId));
        }
        throw new IllegalArgumentException("mapId 与 floorId 至少传一个");
    }

    @Operation(summary = "新建货架", description = "整树提交（footprint + layers + slots），edges 缺省按顶点自动生成闭环")
    @PostMapping
    public ResponseEntity<ApiResponse<ShelfVO>> create(@Valid @RequestBody ShelfSaveDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(shelfService.create(dto)));
    }

    @Operation(summary = "货架详情（整树）")
    @GetMapping("/{id}")
    public ApiResponse<ShelfVO> get(@PathVariable Long id) {
        return ApiResponse.ok(shelfService.get(id));
    }

    @Operation(summary = "更新货架", description = "整树替换：层与格位删除后重建（slot id 会变化）")
    @PutMapping("/{id}")
    public ApiResponse<ShelfVO> update(@PathVariable Long id, @Valid @RequestBody ShelfSaveDTO dto) {
        return ApiResponse.ok(shelfService.update(id, dto));
    }

    @Operation(summary = "删除货架", description = "层与格位级联删除")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        shelfService.delete(id);
        return ApiResponse.ok();
    }
}
