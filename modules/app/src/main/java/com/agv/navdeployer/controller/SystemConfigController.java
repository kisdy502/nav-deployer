package com.agv.navdeployer.controller;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.session.RmsRobotSession;
import com.agv.navdeployer.service.ChargeProperties;
import com.agv.navdeployer.sim.SimAgvProperties;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置可视化（只读）：RMS 开关/zenoh 连接、话题订阅、rosbridge 地址、充电参数等。
 * 面向运维——不改代码就能看本机实际生效的配置。
 */
@Tag(name = "系统配置", description = "当前生效配置总览（只读）：RMS/zenoh、机器人连接、话题、充电参数")
@RestController
@RequestMapping("/api/v1/system")
public class SystemConfigController {

    private static final Pattern ENDPOINTS_PATTERN =
            Pattern.compile("\"endpoints\"\\s*:\\s*\\[([^\\]]*)\\]", Pattern.DOTALL);
    private static final Pattern STRING_VALUE_PATTERN = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern NAMESPACE_PATTERN =
            Pattern.compile("\"namespace\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern MODE_PATTERN =
            Pattern.compile("\"mode\"\\s*:\\s*\"([^\"]*)\"");

    private final RmsProperties rmsProps;
    private final SimAgvProperties simProps;
    private final ChargeProperties chargeProps;
    private final RmsRobotSession rmsSession;
    private final SimAgvTelemetry telemetry;
    private final Environment environment;

    public SystemConfigController(RmsProperties rmsProps,
                                  SimAgvProperties simProps,
                                  ChargeProperties chargeProps,
                                  RmsRobotSession rmsSession,
                                  SimAgvTelemetry telemetry,
                                  Environment environment) {
        this.rmsProps = rmsProps;
        this.simProps = simProps;
        this.chargeProps = chargeProps;
        this.rmsSession = rmsSession;
        this.telemetry = telemetry;
        this.environment = environment;
    }

    @Operation(summary = "配置总览", description = "按 RMS / 机器人连接(rosbridge) / 话题 / 充电分组；"
            + "zenoh endpoints/namespace 解析自实际加载的 config-json5 文件")
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("app", appSection());
        root.put("rms", rmsSection());
        root.put("sim_agv", simAgvSection());
        root.put("charge", chargeSection());
        return ApiResponse.ok(root);
    }

    private Map<String, Object> appSection() {
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("server_port", environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8080")));
        return app;
    }

    private Map<String, Object> rmsSection() {
        Map<String, Object> rms = new LinkedHashMap<>();
        rms.put("enabled", rmsProps.isEnabled());
        rms.put("session_open", rmsSession.isSessionOpen());
        rms.put("registered", rmsSession.isRegistered());
        rms.put("robot", Map.of(
                "robot_code", nullSafe(rmsProps.getRobot().getRobotCode()),
                "robot_sn", nullSafe(rmsProps.getRobot().getRobotSn()),
                "robot_type", nullSafe(rmsProps.getRobot().getRobotType()),
                "robot_name", nullSafe(rmsProps.getRobot().getRobotName()),
                "ip", nullSafe(rmsProps.getRobot().getIp()),
                "capabilities", rmsProps.getRobot().getCapabilities()));
        rms.put("report", Map.of(
                "heartbeat_interval_ms", rmsProps.getReport().getHeartbeatIntervalMs(),
                "status_interval_ms", rmsProps.getReport().getStatusIntervalMs(),
                "verbose_log", rmsProps.getReport().isVerboseLog(),
                "publish_legacy_status", rmsProps.getReport().isPublishLegacyStatus(),
                "publish_body_status", rmsProps.getReport().isPublishBodyStatus()));
        rms.put("zenoh", zenohSection());
        rms.put("behavior", behaviorSection());
        return rms;
    }

    private Map<String, Object> zenohSection() {
        Map<String, Object> zenoh = new LinkedHashMap<>();
        zenoh.put("query_timeout_ms", rmsProps.getZenoh().getQueryTimeoutMs());
        zenoh.put("robot_key_prefix", nullSafe(rmsProps.getZenoh().getRobotKeyPrefix()));
        zenoh.put("register_key", nullSafe(rmsProps.getZenoh().getRegisterKey()));
        zenoh.put("continue_without_register", rmsProps.getZenoh().isContinueWithoutRegister());
        String configFile = rmsProps.getZenoh().getConfigFile();
        String content = readZenohConfig(configFile);
        zenoh.put("config_file", configFile == null || configFile.isBlank()
                ? "classpath:config-json5.json" : configFile);
        if (content != null) {
            zenoh.put("endpoints", extractEndpoints(content));
            zenoh.put("namespace", extractFirst(NAMESPACE_PATTERN, content, ""));
            String mode = extractFirst(MODE_PATTERN, content, null);
            if (mode != null) {
                zenoh.put("mode", mode);
            }
        } else {
            zenoh.put("endpoints", List.of());
            zenoh.put("namespace", null);
            zenoh.put("config_error", "配置文件不可读");
        }
        return zenoh;
    }

    private Map<String, Object> behaviorSection() {
        Map<String, Object> behavior = new LinkedHashMap<>();
        behavior.put("charge_pose", poseMap(rmsProps.getBehavior().getChargePose()));
        behavior.put("home_pose", poseMap(rmsProps.getBehavior().getHomePose()));
        return behavior;
    }

    private Map<String, Object> simAgvSection() {
        Map<String, Object> sim = new LinkedHashMap<>();
        sim.put("enabled", simProps.isEnabled());
        sim.put("ws_url", simProps.getWsUrl());
        sim.put("connected", telemetry.isConnected());
        sim.put("status_fresh_ms", simProps.getStatusFreshMs());
        sim.put("goal_timeout_s", simProps.getGoalTimeoutSeconds());
        sim.put("default_max_speed", simProps.getDefaultMaxSpeed());
        sim.put("default_step", simProps.getDefaultStep());
        sim.put("follow_path_start_radius", simProps.getFollowPathStartRadius());
        sim.put("arm_gate_enabled", simProps.isArmGateEnabled());
        sim.put("arm_strict_unknown", simProps.isArmStrictUnknown());
        sim.put("arm_home_tolerance_rad", simProps.getArmHomeToleranceRad());

        List<Map<String, Object>> topics = new ArrayList<>();
        topics.add(topic("机器人状态", simProps.getStatusTopic(), simProps.getStatusTopicType(), true));
        topics.add(topic("位姿", simProps.getPoseTopic(), simProps.getPoseTopicType(), true));
        topics.add(topic("TF", simProps.getTfTopic(), simProps.getTfTopicType(), true));
        topics.add(topic("里程计", simProps.getOdomTopic(), simProps.getOdomTopicType(), simProps.isOdomEnabled()));
        topics.add(topic("激光雷达 1", simProps.getScanTopic1(), simProps.getScanTopicType(), simProps.isScanEnabled()));
        topics.add(topic("激光雷达 2", simProps.getScanTopic2(), simProps.getScanTopicType(), simProps.isScanEnabled()));
        topics.add(topic("关节状态", simProps.getJointStatesTopic(), simProps.getJointStatesTopicType(),
                simProps.isJointStatesEnabled()));
        topics.add(topic("地图栅格", simProps.getMapTopic(), simProps.getMapTopicType(), simProps.isMapEnabled()));
        topics.add(topic("遥控速度", simProps.getCmdVelTopic(), "geometry_msgs/msg/Twist", true));
        sim.put("topics", topics);

        sim.put("services", Map.of(
                "导航执行(follow_edge)", nullSafe(simProps.getFollowEdgeAction()),
                "急停/恢复(set_control)", nullSafe(simProps.getSetControlService()),
                "重定位", nullSafe(simProps.getRelocalizeService()),
                "机械臂运动", nullSafe(simProps.getArmMoveService()),
                "地图列表", nullSafe(simProps.getListMapsService()),
                "加载地图", nullSafe(simProps.getLoadMapService()),
                "开始建图", nullSafe(simProps.getStartMappingService()),
                "保存地图", nullSafe(simProps.getSaveMapService())));
        sim.put("ws_message_log", Map.of(
                "enabled", simProps.isWsMessageLogEnabled(),
                "max_chars", simProps.getWsMessageLogMaxChars(),
                "topic_interval_ms", simProps.getWsMessageLogTopicIntervalMs()));
        return sim;
    }

    private Map<String, Object> chargeSection() {
        Map<String, Object> charge = new LinkedHashMap<>();
        charge.put("service", chargeProps.getService());
        charge.put("target_battery_pct", chargeProps.getTargetBatteryPct());
        charge.put("service_timeout_ms", chargeProps.getServiceTimeoutMs());
        charge.put("confirm_timeout_ms", chargeProps.getConfirmTimeoutMs());
        charge.put("dock_settle_min_ms", chargeProps.getDockSettleMinMs());
        charge.put("dock_settle_max_ms", chargeProps.getDockSettleMaxMs());
        charge.put("poll_interval_ms", chargeProps.getPollIntervalMs());
        charge.put("stale_grace_ms", chargeProps.getStaleGraceMs());
        charge.put("max_duration_ms", chargeProps.getMaxDurationMs());
        return charge;
    }

    // ==================== zenoh json5 宽松解析 ====================

    private String readZenohConfig(String configFile) {
        try {
            if (configFile == null || configFile.isBlank()) {
                try (var input = new ClassPathResource("config-json5.json").getInputStream()) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return Files.readString(Path.of(configFile.trim()), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return null;
        }
    }

    private static List<String> extractEndpoints(String content) {
        List<String> endpoints = new ArrayList<>();
        Matcher matcher = ENDPOINTS_PATTERN.matcher(content);
        if (matcher.find()) {
            Matcher values = STRING_VALUE_PATTERN.matcher(matcher.group(1));
            while (values.find()) {
                endpoints.add(values.group(1));
            }
        }
        return endpoints;
    }

    private static String extractFirst(Pattern pattern, String content, String fallback) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private static Map<String, Object> topic(String label, String name, String type, boolean enabled) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("topic", nullSafe(name));
        item.put("type", nullSafe(type));
        item.put("enabled", enabled);
        return item;
    }

    private static Map<String, Object> poseMap(RmsProperties.Pose pose) {
        if (pose == null) {
            return null;
        }
        return Map.of("x", pose.getX(), "y", pose.getY(), "yaw", pose.getYaw());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
