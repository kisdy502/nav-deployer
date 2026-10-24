package com.agv.navdeployer.controller.v2;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.metadata.service.RoutePlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * V2 路径规划：Dijkstra 加权最短路径。
 * 支持：距离/时间最优、禁行区避让、减速区惩罚、跨楼层（电梯/楼梯/坡道）。
 */
@Tag(name = "路径规划 v2", description = "基于已部署路线图的 Dijkstra 加权最短路径（时间代价最优）")
@RestController
@RequestMapping("/api/v2/route")
public class RoutePlanController {

    private final RoutePlanService routePlanService;

    public RoutePlanController(RoutePlanService routePlanService) {
        this.routePlanService = routePlanService;
    }

    @Operation(summary = "规划路径",
            description = "Dijkstra 加权最短路径（时间代价）：距离/限速/禁行区/减速区/跨楼层连接。"
                    + "返回途经的路线序列与预计总耗时；无可达路径返回 found=false。")
    @GetMapping("/plan")
    public ApiResponse<RoutePlanService.RoutePlan> plan(
            @RequestParam Long mapId,
            @RequestParam Long fromWaypointId,
            @RequestParam Long toWaypointId) {
        return ApiResponse.ok(routePlanService.plan(mapId, fromWaypointId, toWaypointId));
    }
}
