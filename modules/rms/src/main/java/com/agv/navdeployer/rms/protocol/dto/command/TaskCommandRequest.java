package com.agv.navdeployer.rms.protocol.dto.command;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RMS 任务指令请求（task/add、task/start 等的 payload）。
 * 已知字段强类型化；parameters 为 RMS 协议的动态扩展位
 * （{@code @JsonAnySetter} 收集未知字段），
 * navigate 的 destination{x,y,yaw}/map_name 从中解析。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskCommandRequest(
        String taskId,
        String actionId,
        String cmdId,
        String taskType,
        String taskTemplateType,
        String templateCode,
        String templateId,
        Map<String, Object> parameters
) {
    @JsonCreator
    public TaskCommandRequest {
        parameters = parameters == null ? new LinkedHashMap<>() : parameters;
    }

    @JsonAnySetter
    public void putParameter(String key, Object value) {
        parameters.put(key, value);
    }

    /** 有效 action_id：action_id → cmd_id → task_id 逐级回退（与 mock 一致）。 */
    public String effectiveActionId() {
        if (actionId != null && !actionId.isBlank()) {
            return actionId;
        }
        if (cmdId != null && !cmdId.isBlank()) {
            return cmdId;
        }
        return taskId;
    }

    /** navigate 目标坐标；缺失返回 null。 */
    public Destination destination() {
        Object raw = parameters.get("destination");
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Double x = asDouble(map.get("x"));
        Double y = asDouble(map.get("y"));
        if (x == null || y == null) {
            return null;
        }
        Double yaw = asDouble(map.get("yaw"));
        return new Destination(x, y, yaw == null ? 0.0 : yaw);
    }

    /** RMS 下发的地图名（可空；仿真侧与 RMS 同为 ROS 坐标系，仅记录不切换）。 */
    public String mapName() {
        Object mapName = parameters.get("map_name");
        if (mapName instanceof String name && !name.isBlank()) {
            return name;
        }
        Object map = parameters.get("map");
        return map instanceof String name && !name.isBlank() ? name : null;
    }

    public String parameterText(String key) {
        Object value = parameters.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public record Destination(double x, double y, double yaw) {
    }
}
