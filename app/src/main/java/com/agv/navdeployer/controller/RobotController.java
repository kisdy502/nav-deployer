package com.agv.navdeployer.controller;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.dto.InitialPoseDTO;
import com.agv.navdeployer.dto.RobotControlDTO;
import com.agv.navdeployer.dto.TeleopCommandDTO;
import com.agv.navdeployer.service.MapModeTaskService;
import com.agv.navdeployer.sim.RosCommandDispatcher;
import com.agv.navdeployer.sim.RosCommandDispatcher.SetControlResult;
import com.agv.navdeployer.sim.RosbridgeClient;
import com.agv.navdeployer.sim.SimAgvSsePublisher;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final MapModeTaskService mapModeTaskService;
    private final ObjectMapper objectMapper;
    private final SimAgvTelemetry telemetry;

    public RobotController(SimAgvSsePublisher ssePublisher,
                           RosCommandDispatcher dispatcher,
                           RosbridgeClient rosbridgeClient,
                           MapModeTaskService mapModeTaskService,
                           ObjectMapper objectMapper,
                           SimAgvTelemetry telemetry) {
        this.ssePublisher = ssePublisher;
        this.dispatcher = dispatcher;
        this.rosbridgeClient = rosbridgeClient;
        this.mapModeTaskService = mapModeTaskService;
        this.objectMapper = objectMapper;
        this.telemetry = telemetry;
    }

    @Operation(summary = "遥测快照", description = "连接状态 / 业务状态 / map 系位姿 / 双雷达摘要 / 消息计数 / 地图对齐状态")
    @GetMapping("/api/v1/robot/snapshot")
    public ApiResponse<Object> snapshot() {
        ObjectNode snap = ssePublisher.buildSnapshot();
        snap.set("map_align", objectMapper.valueToTree(mapModeTaskService.computeAlignment()));
        return ApiResponse.ok(snap);
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

    @Operation(summary = "重定位", description = "调机器人 /agv/relocalize 服务：在指定地图坐标重启 Cartographer 定位轨迹")
    @PostMapping("/api/v1/robot/initial-pose")
    public ApiResponse<String> initialPose(@Valid @RequestBody InitialPoseDTO dto) {
        if (!rosbridgeClient.isConnected()) {
            throw new IllegalStateException("rosbridge 未连接，无法重定位");
        }
        String mapName = dto.mapName() == null ? "" : dto.mapName().trim();
        if (mapName.isEmpty()) {
            var status = telemetry.getStatus();
            mapName = status == null ? null : status.mapName();
        }
        if (mapName == null || mapName.isBlank()) {
            throw new IllegalStateException("无法确定目标地图（请求未带 map_name 且 /agv/status 无当前地图），请稍后重试或显式指定 map_name");
        }
        RosCommandDispatcher.RelocalizeResult result;
        try {
            result = dispatcher.callRelocalize(mapName, dto.x(), dto.y(), dto.theta()).join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("重定位服务调用失败: " + cause.getMessage(), cause);
        }
        if (!result.success()) {
            throw new IllegalStateException("重定位被拒绝: " + result.message());
        }
        return ApiResponse.ok(result.mapName());
    }

    @Operation(summary = "全向底盘遥控", description = "发布 /cmd_vel；linear_x 前进、linear_y 左移、angular_z 左转")
    @PostMapping("/api/v1/robot/teleop")
    public ApiResponse<Void> teleop(@Valid @RequestBody TeleopCommandDTO dto) {
        if (!rosbridgeClient.isConnected()) {
            throw new IllegalStateException("rosbridge 未连接，无法发布速度指令");
        }
        dispatcher.publishVelocity(dto.linearX(), dto.linearY(), dto.angularZ());
        return ApiResponse.ok();
    }

    @Operation(summary = "AGV 事件流（SSE）",
            description = "事件：connected / telemetry(1s) / heartbeat(30s) / task(任务创建与状态变更)")
    @GetMapping(value = "/sse/agv", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sse() {
        return ssePublisher.subscribe();
    }
}
