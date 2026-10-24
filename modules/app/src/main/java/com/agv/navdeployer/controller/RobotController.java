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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
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
    private final com.agv.navdeployer.sim.SimAgvProperties simProps;
    private final com.agv.navdeployer.sim.ArmStateMonitor armStateMonitor;

    public RobotController(SimAgvSsePublisher ssePublisher,
                           RosCommandDispatcher dispatcher,
                           RosbridgeClient rosbridgeClient,
                           MapModeTaskService mapModeTaskService,
                           ObjectMapper objectMapper,
                           SimAgvTelemetry telemetry,
                           com.agv.navdeployer.sim.SimAgvProperties simProps,
                           com.agv.navdeployer.sim.ArmStateMonitor armStateMonitor) {
        this.ssePublisher = ssePublisher;
        this.dispatcher = dispatcher;
        this.rosbridgeClient = rosbridgeClient;
        this.mapModeTaskService = mapModeTaskService;
        this.objectMapper = objectMapper;
        this.telemetry = telemetry;
        this.simProps = simProps;
        this.armStateMonitor = armStateMonitor;
    }

    @Operation(summary = "遥测快照", description = "连接状态 / 业务状态 / map 系位姿 / 双雷达摘要 / 消息计数 / 地图对齐状态")
    @GetMapping("/api/v1/robot/snapshot")
    public ApiResponse<Object> snapshot() {
        ObjectNode snap = ssePublisher.buildSnapshot();
        snap.set("map_align", objectMapper.valueToTree(mapModeTaskService.computeAlignment()));
        return ApiResponse.ok(snap);
    }

    @Operation(summary = "机器人 URDF", description = "转发机器人侧 rosparam robot_description（rosapi/get_param），读不到返回 404，前端回退参数化模型")
    @GetMapping(value = "/api/v1/robot/urdf", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> urdf() {
        // 未连接/失败时返回空 body：带纯文本 body 的 XML 响应会让浏览器（直接访问/预览时）报 XML 解析错误
        if (!rosbridgeClient.isConnected()) {
            return ResponseEntity.status(503).header("X-Error", "rosbridge not connected").build();
        }
        try {
            String urdf = dispatcher.getRobotUrdf().join();
            if (urdf == null || urdf.isBlank()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(urdf);
        } catch (CompletionException e) {
            return ResponseEntity.status(502).header("X-Error",
                    "robot_description not available: " + e.getCause().getMessage()).build();
        }
    }

    /** 机械臂调试指令体（契约见《仿真机器人功能开发说明.md》F1.5）。 */
    record ArmMoveDTO(String arm, double[] joints,
                      @com.fasterxml.jackson.annotation.JsonAlias("durationSec") Double durationSec) {
    }

    record ArmHomeDTO(double[] home) {
    }

    @Operation(summary = "设置机械臂归位（联锁基准）", description = "home 为角色顺序 [pan, lift, elbow, wrist1, wrist2, wrist3] 的目标角（弧度），两侧同时生效；前端「设为归位位」调用，覆盖 yaml 默认值（运行期有效，重启回 yaml）")
    @PostMapping("/api/v1/robot/arm-home")
    public ResponseEntity<Object> setArmHome(@RequestBody ArmHomeDTO dto) {
        if (dto.home() == null || dto.home().length != 6) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "home 需要 6 个角色顺序关节角 [pan,lift,elbow,wrist1,wrist2,wrist3]"));
        }
        armStateMonitor.setHomeOverride(dto.home());
        return ResponseEntity.ok(Map.of("success", true, "message", "arm home updated"));
    }

    @Operation(summary = "机械臂运动（调试入口）", description = "转发 /agv/arm_move 服务：arm=left/right/waist，joints 为目标关节角（弧度），异步执行进度见 /joint_states")
    @PostMapping("/api/v1/robot/arm-move")
    public ResponseEntity<Object> armMove(@Valid @RequestBody ArmMoveDTO dto) {
        if (!rosbridgeClient.isConnected()) {
            return ResponseEntity.status(503).body(Map.of("success", false, "message", "rosbridge not connected"));
        }
        if (dto.arm() == null || dto.arm().isBlank() || dto.joints() == null || dto.joints().length == 0) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "arm 与 joints 必填"));
        }
        try {
            var result = dispatcher.armMove(dto.arm().trim(), dto.joints(), dto.durationSec()).join();
            return ResponseEntity.ok(result);
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return ResponseEntity.status(502).body(Map.of("success", false, "message", String.valueOf(cause.getMessage())));
        }
    }

    private static final String MESH_PREFIX = "/api/v1/robot/mesh/";
    private static final java.net.http.HttpClient MESH_CLIENT = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5)).build();

    @Operation(summary = "URDF mesh 代理", description = "把 URDF 的 package:// 路径代理到机器人侧 mesh 静态服务（host 取 ws-url，端口 sim-agv.urdf-mesh-port），浏览器同源加载免 CORS")
    @GetMapping(MESH_PREFIX + "**")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> mesh(
            jakarta.servlet.http.HttpServletRequest request) {
        String raw = request.getRequestURI().substring(request.getContextPath().length() + MESH_PREFIX.length());
        if (raw.isBlank() || raw.contains("..") || raw.contains("\\") || raw.chars().anyMatch(Character::isISOControl)) {
            return ResponseEntity.badRequest().build();
        }
        String host;
        try {
            host = java.net.URI.create(simProps.getWsUrl()).getHost();
        } catch (Exception e) {
            host = null;
        }
        if (host == null || host.isBlank()) {
            return ResponseEntity.status(503).body(out -> out.write("invalid sim-agv.ws-url".getBytes()));
        }
        java.net.URI target = java.net.URI.create("http://" + host + ":" + simProps.getUrdfMeshPort() + "/" + raw);
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(target)
                .timeout(java.time.Duration.ofSeconds(60)).GET().build();
        try {
            java.net.http.HttpResponse<java.io.InputStream> upstream =
                    MESH_CLIENT.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (upstream.statusCode() != 200) {
                return ResponseEntity.status(upstream.statusCode() == 404 ? 404 : 502).build();
            }
            String contentType = upstream.headers().firstValue("Content-Type").orElse("application/octet-stream");
            return ResponseEntity.ok()
                    .header("Content-Type", contentType)
                    .body(out -> upstream.body().transferTo(out));
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ResponseEntity.status(502)
                    .body(out -> out.write(("robot mesh service unavailable: " + e.getMessage()).getBytes()));
        }
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
