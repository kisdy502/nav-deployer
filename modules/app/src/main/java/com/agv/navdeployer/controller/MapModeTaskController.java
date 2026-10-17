package com.agv.navdeployer.controller;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.dto.SaveMapDTO;
import com.agv.navdeployer.service.MapModeTaskService;
import com.agv.navdeployer.vo.MapModeTaskVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "地图模式任务", description = "切图 / 建图 / 保存地图任务跟踪；SSE 事件 map-task、map-sync 同步推送")
@RestController
@RequestMapping("/api/v1/map-mode-tasks")
public class MapModeTaskController {

    private final MapModeTaskService mapModeTaskService;

    public MapModeTaskController(MapModeTaskService mapModeTaskService) {
        this.mapModeTaskService = mapModeTaskService;
    }

    @Operation(summary = "当前任务", description = "最近一条任务（含终态），无任务 data=null")
    @GetMapping("/current")
    public ApiResponse<MapModeTaskVO> current() {
        return ApiResponse.ok(mapModeTaskService.currentTask());
    }

    @Operation(summary = "任务历史", description = "最近的模式任务（新在前，内存态保留 100 条）")
    @GetMapping("/recent")
    public ApiResponse<List<MapModeTaskVO>> recent(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(mapModeTaskService.recent(limit));
    }

    @Operation(summary = "进入在线建图",
            description = "调 /agv/start_mapping：机器人停定位、拉起建图（mode=MAPPING，导航被拒，/cmd_vel 遥控可用）。"
                    + "前置：连接正常、状态新鲜、mode=NAVIGATION、无在途任务")
    @PostMapping("/start-mapping")
    public ApiResponse<MapModeTaskVO> startMapping() {
        return ApiResponse.ok(mapModeTaskService.startMapping());
    }

    @Operation(summary = "保存建图并回到定位",
            description = "调 /agv/save_map：机器人存 pbstream -> 转 pgm/yaml -> 停建图 -> 拉定位"
                    + "（MAPPING -> RELOCALIZING -> NAVIGATION）。成功后自动 get_map 同步栅格入库并激活（SSE map-sync 事件）。"
                    + "前置：mode=MAPPING")
    @PostMapping("/save-map")
    public ApiResponse<MapModeTaskVO> saveMap(@Valid @RequestBody SaveMapDTO dto) {
        return ApiResponse.ok(mapModeTaskService.saveMap(dto.mapName()));
    }
}
