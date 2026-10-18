package com.agv.navdeployer.rms.map;

import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.protocol.dto.command.BodyReply;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RMS 遥测与配置类指令：pose/query、config/get、config/edit。
 *
 * <p>pose/query 返回底盘当前 map 系位姿（来自 SimAgvTelemetry）；
 * config/get 返回运行时配置快照（静态属性 + config/edit 下发的覆盖项）；
 * config/edit 接受分模块配置覆盖（内存生效，重启丢失——仿真场景够用）。
 */
public class RmsTelemetryService {

    private static final Logger log = LoggerFactory.getLogger(RmsTelemetryService.class);

    private final SimAgvTelemetry telemetry;
    private final RmsProperties props;
    private final ObjectMapper mapper;

    /** config/edit 下发的覆盖项（模块名 → 参数 Map），线程安全。 */
    private final Map<String, Map<String, Object>> configOverrides = new ConcurrentHashMap<>();

    public RmsTelemetryService(SimAgvTelemetry telemetry, RmsProperties props, ObjectMapper mapper) {
        this.telemetry = telemetry;
        this.props = props;
        this.mapper = mapper;
    }

    /** pose/query：底盘位姿 + 臂/腰关节角（/joint_states 按命名分组），末端 vector 无数据源暂全零。 */
    public BodyReply poseQuery() {
        SimAgvTelemetry.PoseSnapshot pose = telemetry.getMapPose();
        double x = pose == null ? 0.0 : pose.x();
        double y = pose == null ? 0.0 : pose.y();
        double yaw = pose == null ? 0.0 : pose.yaw();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agv_vector", new double[]{x, y, yaw});
        data.put("head_vector", new double[]{0, 0, 0, 0, 0, 0});
        data.put("leftarm_vector", new double[]{0, 0, 0, 0, 0, 0});
        data.put("rightarm_vector", new double[]{0, 0, 0, 0, 0, 0});
        appendGroupedJoints(data, telemetry.getJointStates());
        return BodyReply.success("ok", data);
    }

    /**
     * 关节分组（命名契约见《仿真机器人功能开发说明.md》§2.4）：
     * 名含 arm+left → left_joints，arm+right → right_joints，waist → waist_joints；
     * 组内按关节名自然排序（arm_left_joint_1..7）；舵轮（steer）与未知关节不下发。
     */
    static void appendGroupedJoints(Map<String, Object> data, SimAgvTelemetry.JointSnapshot joints) {
        Map<String, Double> left = new java.util.TreeMap<>();
        Map<String, Double> right = new java.util.TreeMap<>();
        Map<String, Double> waist = new java.util.TreeMap<>();
        if (joints != null) {
            String[] names = joints.names();
            double[] positions = joints.positions();
            for (int i = 0; i < names.length && i < positions.length; i++) {
                String lower = names[i].toLowerCase();
                if (lower.contains("arm") && lower.contains("left")) {
                    left.put(names[i], positions[i]);
                } else if (lower.contains("arm") && lower.contains("right")) {
                    right.put(names[i], positions[i]);
                } else if (lower.contains("waist")) {
                    waist.put(names[i], positions[i]);
                }
            }
        }
        data.put("left_joints", left.values().stream().mapToDouble(Double::doubleValue).toArray());
        data.put("right_joints", right.values().stream().mapToDouble(Double::doubleValue).toArray());
        data.put("waist_joints", waist.values().stream().mapToDouble(Double::doubleValue).toArray());
    }

    /** config/get：静态属性 + config/edit 覆盖项合并后的快照。 */
    public BodyReply configGet() {
        Map<String, Object> config = new LinkedHashMap<>();
        RmsProperties.Robot robot = props.getRobot();
        config.put("robot_info", Map.of(
                "alias_name", robot.getRobotName() == null ? "" : robot.getRobotName(),
                "robot_type", robot.getRobotType() == null ? "" : robot.getRobotType()));
        config.put("task_config", Map.of(
                "task_result_timeout_seconds", 300,
                "task_execute_timeout_seconds", 300));
        if (!configOverrides.isEmpty()) {
            config.putAll(configOverrides);
        }
        return BodyReply.success("ok", config);
    }

    /** config/edit：接受分模块配置覆盖，内存生效。 */
    public BodyReply configEdit(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return BodyReply.failure(400, "缺少配置内容");
        }
        try {
            JsonNode root = mapper.readTree(rawPayload);
            if (!root.isObject() || root.isEmpty()) {
                return BodyReply.failure(400, "配置内容须为非空 JSON 对象");
            }
            root.fields().forEachRemaining(entry -> {
                String module = entry.getKey();
                JsonNode value = entry.getValue();
                if (value.isObject()) {
                    Map<String, Object> moduleConfig = new LinkedHashMap<>();
                    value.fields().forEachRemaining(field ->
                            moduleConfig.put(field.getKey(), field.getValue() instanceof JsonNode n
                                    ? mapper.convertValue(n, Object.class) : field.getValue()));
                    configOverrides.put(module, moduleConfig);
                }
            });
            log.info("RMS config/edit applied: {} module(s) overridden", configOverrides.size());
            return BodyReply.success("config applied", null);
        } catch (Exception exception) {
            return BodyReply.failure(400, "配置内容解析失败: " + exception.getMessage());
        }
    }
}
