package com.agv.navdeployer.rms.interfaces;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.rms.application.RmsTaskService;
import com.agv.navdeployer.rms.application.dto.RmsTaskDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    public ApiResponse<List<RmsTaskDTO>> list(@RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(taskService.listTasks(limit == null ? 50 : limit));
    }

    @Operation(summary = "当前在途任务", description = "RUNNING/PAUSED 的唯一任务，无则 data=null")
    @GetMapping("/active")
    public ApiResponse<RmsTaskDTO> active() {
        return ApiResponse.ok(taskService.activeTaskView());
    }

    @Operation(summary = "复位（强制收口）", description = "所有非终态任务强制置 canceled：取消内部移动/充电、"
            + "上报 RMS result_report、释放 busy 闸门。适用：任务卡中间态后 RMS 新任务被 409 拒绝")
    @PostMapping("/reset")
    public ApiResponse<List<RmsTaskDTO>> reset() {
        return ApiResponse.ok(taskService.resetInFlight("人工复位（上位机任务管理页）"));
    }
}
