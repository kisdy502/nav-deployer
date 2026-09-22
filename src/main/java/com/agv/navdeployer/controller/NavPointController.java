package com.agv.navdeployer.controller;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.dto.NavPointCreateDTO;
import com.agv.navdeployer.dto.NavPointFromPoseDTO;
import com.agv.navdeployer.dto.NavPointUpdateDTO;
import com.agv.navdeployer.service.NavPointService;
import com.agv.navdeployer.vo.NavPointVO;
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

@Tag(name = "点位部署", description = "地图点位 CRUD 与当前位姿标记")
@RestController
@RequestMapping("/api/v1/nav-points")
public class NavPointController {

    private final NavPointService navPointService;

    public NavPointController(NavPointService navPointService) {
        this.navPointService = navPointService;
    }

    @Operation(summary = "创建点位")
    @PostMapping
    public ResponseEntity<ApiResponse<NavPointVO>> create(@Valid @RequestBody NavPointCreateDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(navPointService.create(dto)));
    }

    @Operation(summary = "标记当前位姿为点位", description = "读机器人 map 系实时位姿建点（无页面时的实操部署方式）")
    @PostMapping("/from-current-pose")
    public ResponseEntity<ApiResponse<NavPointVO>> createFromCurrentPose(
            @Valid @RequestBody NavPointFromPoseDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(navPointService.createFromCurrentPose(dto)));
    }

    @Operation(summary = "点位列表", description = "map_id 必填")
    @GetMapping
    public ApiResponse<List<NavPointVO>> list(@RequestParam Long mapId) {
        return ApiResponse.ok(navPointService.list(mapId));
    }

    @Operation(summary = "点位详情")
    @GetMapping("/{id}")
    public ApiResponse<NavPointVO> get(@PathVariable Long id) {
        return ApiResponse.ok(navPointService.get(id));
    }

    @Operation(summary = "更新点位")
    @PutMapping("/{id}")
    public ApiResponse<NavPointVO> update(@PathVariable Long id, @Valid @RequestBody NavPointUpdateDTO dto) {
        return ApiResponse.ok(navPointService.update(id, dto));
    }

    @Operation(summary = "删除点位", description = "被路线边引用时拒绝")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        navPointService.delete(id);
        return ApiResponse.ok();
    }
}
