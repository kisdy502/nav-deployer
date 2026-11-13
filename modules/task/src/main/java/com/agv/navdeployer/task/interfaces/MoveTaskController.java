package com.agv.navdeployer.task.interfaces;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.task.application.cmd.MoveTaskCreateCmd;
import com.agv.navdeployer.task.application.MoveTaskService;
import com.agv.navdeployer.task.application.dto.MoveTaskDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "移动任务", description = "下发 follow_edge 移动任务（到点位 / 到坐标 / 按路线），进度与终态闭环")
@RestController
@RequestMapping("/api/v1/move-tasks")
public class MoveTaskController {

    private final MoveTaskService moveTaskService;

    public MoveTaskController(MoveTaskService moveTaskService) {
        this.moveTaskService = moveTaskService;
    }

    @Operation(summary = "创建并下发移动任务",
            description = "TO_WAYPOINT 需 waypoint_id（须位于 PUBLISHED 地图，坐标创建时快照）；"
                    + "GOAL 需 x/y/theta；FOLLOW_PATH 需 path_id（须 DEPLOYED）")
    @PostMapping
    public ResponseEntity<ApiResponse<MoveTaskDTO>> create(@Valid @RequestBody MoveTaskCreateCmd cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(moveTaskService.create(cmd)));
    }

    @Operation(summary = "取消在途任务", description = "cancel_action_goal，终态由 action_result 确认；暂停中任务可直接取消")
    @PostMapping("/{id}/cancel")
    public ApiResponse<MoveTaskDTO> cancel(@PathVariable Long id) {
        return ApiResponse.ok(moveTaskService.cancel(id));
    }

    @Operation(summary = "暂停在途任务（立即停）",
            description = "cancel_goal 中断当前段机器人就地停住，任务转 PAUSED；恢复时从当前位置重规划续走")
    @PostMapping("/{id}/pause")
    public ApiResponse<MoveTaskDTO> pause(@PathVariable Long id) {
        return ApiResponse.ok(moveTaskService.pause(id));
    }

    @Operation(summary = "恢复暂停任务",
            description = "规划器从机器人当前位置重算到原目标的接续路线，替换剩余段后继续执行")
    @PostMapping("/{id}/resume")
    public ApiResponse<MoveTaskDTO> resume(@PathVariable Long id) {
        return ApiResponse.ok(moveTaskService.resume(id));
    }

    @Operation(summary = "当前在途任务", description = "无在途任务时 data=null")
    @GetMapping("/active")
    public ApiResponse<MoveTaskDTO> active() {
        return ApiResponse.ok(moveTaskService.active());
    }

    @Operation(summary = "任务列表", description = "可按 status 过滤，新→旧，默认 50 条")
    @GetMapping
    public ApiResponse<List<MoveTaskDTO>> list(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(moveTaskService.list(status, limit));
    }

    @Operation(summary = "任务详情", description = "含实时 feedback 位姿与 FOLLOW_PATH 段进度")
    @GetMapping("/{id}")
    public ApiResponse<MoveTaskDTO> get(@PathVariable Long id) {
        return ApiResponse.ok(moveTaskService.get(id));
    }

    @Operation(summary = "任务分段执行记录",
            description = "一次导航的每段执行明细（起止点位、状态、耗时、消息）；仅新任务有记录，历史任务为空")
    @GetMapping("/{id}/segments")
    public ApiResponse<List<com.agv.navdeployer.task.domain.movetask.MoveTaskSegment>> segments(@PathVariable Long id) {
        return ApiResponse.ok(moveTaskService.listSegments(id));
    }
}
