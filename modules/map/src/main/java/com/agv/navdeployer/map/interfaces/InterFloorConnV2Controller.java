package com.agv.navdeployer.map.interfaces;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.map.application.cmd.ConnCreateCmd;
import com.agv.navdeployer.map.application.cmd.ConnUpdateCmd;
import com.agv.navdeployer.map.application.MapConnService;
import com.agv.navdeployer.map.application.dto.ConnDTO;
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

@Tag(name = "楼层间连接 v2", description = "elevator 电梯 / stairs 楼梯 / ramp 坡道（端点=楼层编码+点位编码）")
@RestController
@RequestMapping("/api/v2/map-inter-floor-conns")
public class InterFloorConnV2Controller {

    private final MapConnService connService;

    public InterFloorConnV2Controller(MapConnService connService) {
        this.connService = connService;
    }

    @Operation(summary = "连接列表", description = "mapId 必填")
    @GetMapping
    public ApiResponse<List<ConnDTO>> list(@RequestParam Long mapId) {
        return ApiResponse.ok(connService.list(mapId));
    }

    @Operation(summary = "新建连接")
    @PostMapping
    public ResponseEntity<ApiResponse<ConnDTO>> create(@Valid @RequestBody ConnCreateCmd dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(connService.create(dto)));
    }

    @Operation(summary = "连接详情")
    @GetMapping("/{id}")
    public ApiResponse<ConnDTO> get(@PathVariable Long id) {
        return ApiResponse.ok(connService.get(id));
    }

    @Operation(summary = "更新连接")
    @PutMapping("/{id}")
    public ApiResponse<ConnDTO> update(@PathVariable Long id, @Valid @RequestBody ConnUpdateCmd dto) {
        return ApiResponse.ok(connService.update(id, dto));
    }

    @Operation(summary = "删除连接")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        connService.delete(id);
        return ApiResponse.ok();
    }
}
