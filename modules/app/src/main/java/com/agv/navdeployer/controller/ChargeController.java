package com.agv.navdeployer.controller;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.service.ChargeService;
import com.agv.navdeployer.vo.ChargeTaskVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 充电任务（前端「去充电」入口；RMS charge 任务走 ChargeService 同一底层链路）。
 */
@Tag(name = "充电任务", description = "导航到充电点 → 开始充电 → 充到目标电量（阶段进度经 SSE charge 事件推送）")
@RestController
@RequestMapping("/api/v1/charge")
public class ChargeController {

    private final ChargeService chargeService;

    public ChargeController(ChargeService chargeService) {
        this.chargeService = chargeService;
    }

    public record ChargeStartDTO(
            Long waypointId,
            Double x,
            Double y,
            Double theta,
            Double targetBatteryPct
    ) {
    }

    @Operation(summary = "发起充电", description = "waypoint_id（v2 充电点）优先，缺省用 x/y/theta；"
            + "target_battery_pct 缺省用 charge.target_battery_pct 配置。导航闸门失败返回 400")
    @PostMapping("/start")
    public ApiResponse<ChargeTaskVO> start(@Valid @RequestBody ChargeStartDTO dto) {
        return ApiResponse.ok(chargeService.start(new ChargeService.ChargeCommand(
                "manual", dto.waypointId(), dto.x(), dto.y(), dto.theta(), dto.targetBatteryPct())));
    }

    @Operation(summary = "进行中的充电任务", description = "无进行中任务时 data=null")
    @GetMapping("/active")
    public ApiResponse<ChargeTaskVO> active() {
        return ApiResponse.ok(chargeService.active());
    }

    @Operation(summary = "充电任务历史", description = "最新在前（含进行中置顶），内存环形 100 条")
    @GetMapping("/tasks")
    public ApiResponse<java.util.List<ChargeTaskVO>> tasks(
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(chargeService.history(limit == null ? 50 : limit));
    }

    @Operation(summary = "取消充电", description = "导航阶段连带取消移动；在桩上则调用停止充电（data=false）")
    @PostMapping("/cancel")
    public ApiResponse<ChargeTaskVO> cancel() {
        return ApiResponse.ok(chargeService.cancel("手动取消"));
    }

    @Operation(summary = "救援充电", description = "跳过导航直接远程开始充电（机器人侧不校验是否在桩上）。"
            + "适用：电量耗尽被拒绝一切移动指令、无法导航到桩的死锁救援。流程仍含停稳等待与遥测确认")
    @PostMapping("/rescue")
    public ApiResponse<ChargeTaskVO> rescue() {
        return ApiResponse.ok(chargeService.startRescue());
    }
}
