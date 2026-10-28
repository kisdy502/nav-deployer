package com.agv.navdeployer.controller;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.rms.task.RmsTaskService;
import com.agv.navdeployer.rms.task.RmsTaskVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RMS 任务运维（任务管理页）：执行历史、当前任务、人工复位。
 * 复位 = 把卡在中间态的任务强制收口（取消内部移动/充电 + 上报 RMS + 释放 busy 闸门）。
 */
@Tag(name = "RMS 任务运维", description = "任务执行记录 / 当前状态 / 卡死复位（复位后 RMS 可继续下发新任务）")
@RestController
@RequestMapping("/api/v1/rms/tasks")
public class RmsTaskOpsController {

    private final RmsTaskService taskService;

    public RmsTaskOpsController(RmsTaskService taskService) {
        this.taskService = taskService;
    }

    @Operation(summary = "任务执行历史", description = "DB 记录新→旧（跨重启）；limit 默认 50")
    @GetMapping
    public ApiResponse<List<RmsTaskVO>> list(@RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(taskService.listTasks(limit == null ? 50 : limit));
    }

    @Operation(summary = "当前在途任务", description = "RUNNING/PAUSED 的唯一任务，无则 data=null")
    @GetMapping("/active")
    public ApiResponse<RmsTaskVO> active() {
        return ApiResponse.ok(taskService.activeTaskView());
    }

    @Operation(summary = "复位（强制收口）", description = "所有非终态任务强制置 canceled：取消内部移动/充电、"
            + "上报 RMS result_report、释放 busy 闸门。适用：任务卡中间态后 RMS 新任务被 409 拒绝")
    @PostMapping("/reset")
    public ApiResponse<List<RmsTaskVO>> reset() {
        return ApiResponse.ok(taskService.resetInFlight("人工复位（上位机任务管理页）"));
    }
}
