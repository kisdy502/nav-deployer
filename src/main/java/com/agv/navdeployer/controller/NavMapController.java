package com.agv.navdeployer.controller;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.dto.MapSwitchDTO;
import com.agv.navdeployer.dto.NavMapCreateDTO;
import com.agv.navdeployer.dto.NavMapRobotNameDTO;
import com.agv.navdeployer.dto.NavMapUpdateDTO;
import com.agv.navdeployer.service.MapModeTaskService;
import com.agv.navdeployer.service.NavMapService;
import com.agv.navdeployer.vo.MapGridVO;
import com.agv.navdeployer.vo.MapModeTaskVO;
import com.agv.navdeployer.vo.NavMapVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "地图管理", description = "建图快照、地图增删改查、激活部署")
@RestController
@RequestMapping("/api/v1/nav-maps")
public class NavMapController {

    private final NavMapService navMapService;
    private final MapModeTaskService mapModeTaskService;

    public NavMapController(NavMapService navMapService, MapModeTaskService mapModeTaskService) {
        this.navMapService = navMapService;
        this.mapModeTaskService = mapModeTaskService;
    }

    @Operation(summary = "实时栅格", description = "/map 最新一帧（OccupancyGrid 展平视图）")
    @GetMapping("/live")
    public ApiResponse<MapGridVO> live() {
        return ApiResponse.ok(navMapService.liveGrid());
    }

    @Operation(summary = "从实时快照建图", description = "把当前 /map 栅格保存为新地图（gzip 存 MinIO）")
    @PostMapping
    public ResponseEntity<ApiResponse<NavMapVO>> create(@Valid @RequestBody NavMapCreateDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(navMapService.createFromLive(dto.mapName())));
    }

    @Operation(summary = "地图列表")
    @GetMapping
    public ApiResponse<List<NavMapVO>> list() {
        return ApiResponse.ok(navMapService.list());
    }

    @Operation(summary = "地图详情")
    @GetMapping("/{id}")
    public ApiResponse<NavMapVO> get(@PathVariable Long id) {
        return ApiResponse.ok(navMapService.get(id));
    }

    @Operation(summary = "地图栅格数据", description = "返回建图时保存的 OccupancyGrid JSON（application/json）")
    @GetMapping("/{id}/data")
    public ResponseEntity<byte[]> data(@PathVariable Long id) {
        NavMapService.StoredGrid grid = navMapService.getGridData(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(grid.json());
    }

    @Operation(summary = "重命名地图")
    @PutMapping("/{id}")
    public ApiResponse<NavMapVO> rename(@PathVariable Long id, @Valid @RequestBody NavMapUpdateDTO dto) {
        return ApiResponse.ok(navMapService.rename(id, dto.mapName()));
    }

    @Operation(summary = "设置机器人侧地图名",
            description = "机器人 maps_dir 三件套的文件名主干；切图时下发给 /agv/load_map。缺省回退用 mapName")
    @PutMapping("/{id}/robot-map-name")
    public ApiResponse<NavMapVO> setRobotMapName(@PathVariable Long id,
                                                 @Valid @RequestBody NavMapRobotNameDTO dto) {
        return ApiResponse.ok(navMapService.setRobotMapName(id, dto.robotMapName()));
    }

    @Operation(summary = "切换机器人地图",
            description = "调 /agv/load_map 重启定位加载新图（受理即返回）。终态看任务状态："
                    + "mode RELOCALIZING -> NAVIGATION 且 map_name=目标 即 SUCCEEDED，同时该地图置 ACTIVE。"
                    + "前置：连接正常、状态新鲜、mode=NAVIGATION、无在途移动任务")
    @PostMapping("/{id}/switch")
    public ApiResponse<MapModeTaskVO> switchMap(@PathVariable Long id,
                                                @RequestBody(required = false) MapSwitchDTO dto) {
        return ApiResponse.ok(mapModeTaskService.switchMap(id, dto == null ? null : dto.robotMapName()));
    }

    @Operation(summary = "激活地图", description = "设为当前部署地图（全局唯一 ACTIVE，其余归档）")
    @PostMapping("/{id}/activate")
    public ApiResponse<NavMapVO> activate(@PathVariable Long id) {
        return ApiResponse.ok(navMapService.activate(id));
    }

    @Operation(summary = "删除地图", description = "有关联点位/路线或处于 ACTIVE 时拒绝")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        navMapService.delete(id);
        return ApiResponse.ok();
    }
}
