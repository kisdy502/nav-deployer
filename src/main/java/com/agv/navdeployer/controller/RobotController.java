package com.agv.navdeployer.controller;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.dto.InitialPoseDTO;
import com.agv.navdeployer.dto.RobotControlDTO;
import com.agv.navdeployer.sim.RosCommandDispatcher;
import com.agv.navdeployer.sim.RosCommandDispatcher.SetControlResult;
import com.agv.navdeployer.sim.RosbridgeClient;
import com.agv.navdeployer.sim.SimAgvSsePublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletionException;

@Tag(name = "机器人状态与控制", description = "遥测快照、set_control 任务闸门、重定位、SSE 事件流")
@RestController
public class RobotController {

    private final SimAgvSsePublisher ssePublisher;
    private final RosCommandDispatcher dispatcher;
    private final RosbridgeClient rosbridgeClient;

    public RobotController(SimAgvSsePublisher ssePublisher,
                           RosCommandDispatcher dispatcher,
                           RosbridgeClient rosbridgeClient) {
        this.ssePublisher = ssePublisher;
        this.dispatcher = dispatcher;
        this.rosbridgeClient = rosbridgeClient;
    }

    @Operation(summary = "遥测快照", description = "连接状态 / 业务状态 / map 系位姿 / 双雷达摘要 / 消息计数")
    @GetMapping("/api/v1/robot/snapshot")
    public ApiResponse<Object> snapshot() {
        return ApiResponse.ok(ssePublisher.buildSnapshot());
    }

    @Operation(summary = "任务闸门", description = "start 恢复接单 / stop 停车暂停 / reset 复位（同步等 service_response）")
    @PostMapping("/api/v1/robot/control")
    public ApiResponse<SetControlResult> control(@Valid @RequestBody RobotControlDTO dto) {
        if (!rosbridgeClient.isConnected()) {
            throw new IllegalStateException("rosbridge 未连接，无法调用 set_control");
        }
        try {
            return ApiResponse.ok(dispatcher.setControl(dto.action()).join());
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(cause.getMessage(), cause);
        }
    }

    @Operation(summary = "重定位", description = "发布 /initialpose（PoseWithCovarianceStamped）纠正定位")
    @PostMapping("/api/v1/robot/initial-pose")
    public ApiResponse<Void> initialPose(@Valid @RequestBody InitialPoseDTO dto) {
        if (!rosbridgeClient.isConnected()) {
            throw new IllegalStateException("rosbridge 未连接，无法发布 initialpose");
        }
        dispatcher.publishInitialPose(dto.x(), dto.y(), dto.theta());
        return ApiResponse.ok();
    }

    @Operation(summary = "AGV 事件流（SSE）",
            description = "事件：connected / telemetry(1s) / heartbeat(30s) / task(任务创建与状态变更)")
    @GetMapping(value = "/sse/agv", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sse() {
        return ssePublisher.subscribe();
    }
}
