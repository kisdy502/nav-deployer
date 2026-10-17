package com.agv.navdeployer.controller;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.dto.NavPathCreateDTO;
import com.agv.navdeployer.dto.NavPathEdgesUpdateDTO;
import com.agv.navdeployer.dto.NavPathUpdateDTO;
import com.agv.navdeployer.service.NavPathService;
import com.agv.navdeployer.vo.NavPathVO;
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

@Tag(name = "路线部署", description = "路线（有序边序列）CRUD、边序列替换与部署")
@RestController
@RequestMapping("/api/v1/nav-paths")
public class NavPathController {

    private final NavPathService navPathService;

    public NavPathController(NavPathService navPathService) {
        this.navPathService = navPathService;
    }

    @Operation(summary = "创建路线")
    @PostMapping
    public ResponseEntity<ApiResponse<NavPathVO>> create(@Valid @RequestBody NavPathCreateDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(navPathService.create(dto)));
    }

    @Operation(summary = "路线列表", description = "map_id 必填，不含边")
    @GetMapping
    public ApiResponse<List<NavPathVO>> list(@RequestParam Long mapId) {
        return ApiResponse.ok(navPathService.list(mapId));
    }

    @Operation(summary = "路线详情", description = "含有序边与点位编码")
    @GetMapping("/{id}")
    public ApiResponse<NavPathVO> get(@PathVariable Long id) {
        return ApiResponse.ok(navPathService.get(id));
    }

    @Operation(summary = "更新路线基本信息")
    @PutMapping("/{id}")
    public ApiResponse<NavPathVO> update(@PathVariable Long id, @Valid @RequestBody NavPathUpdateDTO dto) {
        return ApiResponse.ok(navPathService.update(id, dto));
    }

    @Operation(summary = "整体替换边序列", description = "校验同图 / 连续性 / 曲线控制点 1~2 个；替换后回到 DRAFT")
    @PutMapping("/{id}/edges")
    public ApiResponse<NavPathVO> replaceEdges(@PathVariable Long id,
                                               @Valid @RequestBody NavPathEdgesUpdateDTO dto) {
        return ApiResponse.ok(navPathService.replaceEdges(id, dto));
    }

    @Operation(summary = "部署路线", description = "要求已配置至少一条边；仅 DEPLOYED 路线可被 FOLLOW_PATH 执行")
    @PostMapping("/{id}/deploy")
    public ApiResponse<NavPathVO> deploy(@PathVariable Long id) {
        return ApiResponse.ok(navPathService.deploy(id));
    }

    @Operation(summary = "删除路线", description = "边级联删除")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        navPathService.delete(id);
        return ApiResponse.ok();
    }
}
