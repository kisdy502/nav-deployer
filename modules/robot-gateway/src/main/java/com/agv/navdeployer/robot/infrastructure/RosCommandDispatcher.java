package com.agv.navdeployer.robot.infrastructure;

import com.agv.navdeployer.robot.infrastructure.RosbridgeClient;
import com.agv.navdeployer.robot.infrastructure.RosbridgeHandler;
import com.agv.navdeployer.robot.domain.LocalizationOperation;

import com.agv.navdeployer.robot.domain.SimAgvProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public class RosCommandDispatcher implements RosbridgeHandler {

    private static final Logger log = LoggerFactory.getLogger(RosCommandDispatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SimAgvProperties props;
    private final ConcurrentHashMap<String, ActionListener> actionListeners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingServices = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong();

    private RosbridgeClient client;

    public RosCommandDispatcher(SimAgvProperties props) {
        this.props = props;
    }

    public void attach(RosbridgeClient client) {
        this.client = client;
    }

    public String dispatchFollowEdge(FollowEdgeGoal goal, ActionListener listener) {
        requireClient();
        String goalId = "goal-" + idSequence.incrementAndGet();
        actionListeners.put(goalId, listener);
        log.info("dispatch follow_edge: goal={}, command_id={}, node_id={}, target=({}, {}, {}rad), edge_type={}, max_speed={}, step={}, end_point={}, control_points={}",
                goalId, goal.commandId(), goal.nodeId(), goal.x(), goal.y(), goal.theta(),
                goal.edgeType(), goal.maxSpeed(), goal.step(), goal.endPoint(), goal.controlPoints().size());
        client.send(buildFollowEdgeOp(goalId, props.getFollowEdgeAction(), props.getFollowEdgeActionType(), goal));
        return goalId;
    }

    public void cancelGoal(String goalId) {
        requireClient();
        ObjectNode operation = MAPPER.createObjectNode();
        operation.put("op", "cancel_action_goal");
        operation.put("id", goalId);
        operation.put("action", props.getFollowEdgeAction());
        client.send(operation);
    }

    public CompletableFuture<SetControlResult> setControl(String action) {
        requireClient();
        ObjectNode args = MAPPER.createObjectNode();
        args.put("action", action);
        return callService(props.getSetControlService(), args)
                .thenApply(values -> new SetControlResult(
                        values.path("success").asBoolean(false),
                        values.path("message").asText(""),
                        values.path("state").asText(null)))
                .exceptionally(throwable -> {
                    if (throwable.getCause() instanceof TimeoutException
                            || throwable instanceof TimeoutException) {
                        throw new IllegalStateException(
                                "set_control 响应超时（" + props.getServiceTimeoutMs() + "ms），请检查仿真栈");
                    }
                    if (throwable instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new IllegalStateException("set_control 调用失败: " + throwable.getMessage(), throwable);
                });
    }

    public CompletableFuture<JsonNode> callService(String service, JsonNode args) {
        return callService(service, args, props.getServiceTimeoutMs());
    }

    public CompletableFuture<String> getRobotUrdf() {
        requireClient();
        long start = System.currentTimeMillis();
        ObjectNode args = MAPPER.createObjectNode();
        args.put("name", "/robot_state_publisher:robot_description");
        return callService("/rosapi/get_param", args)
                .thenApply(values -> {
                    String value = values.path("value").asText(null);
                    log.info("get_param(robot_description) 耗时{}ms len={}B",
                            System.currentTimeMillis() - start,
                            value == null ? -1 : value.length());
                    return urdfOrNull(value);
                })
                .thenCompose(urdf -> urdf != null
                        ? CompletableFuture.completedFuture(urdf)
                        : callService("/rosapi/get_param", bareNameArgs()).thenApply(values ->
                                urdfOrNull(values.path("value").asText(null))))
                .exceptionally(throwable -> {
                    log.warn("get_robot_urdf failed: {}", throwable.getMessage());
                    return null;
                });
    }

    private ObjectNode bareNameArgs() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("name", "robot_description");
        return args;
    }

    private String urdfOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String urdf = value;
        if (urdf.startsWith("\"")) {
            // 部分 rosapi 版本对 string 参数也做 json.dumps（双重编码），再解一层
            try {
                urdf = MAPPER.readTree(urdf).asText();
            } catch (Exception exception) {
                return null;
            }
        }
        return urdf.startsWith("<?xml") ? urdf : null;
    }

    public record ArmMoveResult(boolean success, String message) {
    }

    private final java.util.concurrent.atomic.AtomicInteger armMoveInFlight =
            new java.util.concurrent.atomic.AtomicInteger();

    public int getArmMoveInFlight() {
        return armMoveInFlight.get();
    }

    public CompletableFuture<ArmMoveResult> armMove(String arm, double[] joints, Double durationSec) {
        requireClient();
        ObjectNode args = MAPPER.createObjectNode();
        args.put("arm", arm);
        ArrayNode jointArray = args.putArray("joints");
        for (double joint : joints) {
            jointArray.add(joint);
        }
        if (durationSec != null) {
            args.put("duration_sec", durationSec);
        }
        armMoveInFlight.incrementAndGet();
        return callService(props.getArmMoveService(), args)
                .thenApply(values -> new ArmMoveResult(
                        values.path("success").asBoolean(false),
                        values.path("message").asText("")))
                .exceptionally(throwable -> new ArmMoveResult(false,
                        "arm_move 调用失败: " + throwable.getMessage()))
                .whenComplete((result, error) -> armMoveInFlight.decrementAndGet());
    }

    public CompletableFuture<JsonNode> callService(String service, JsonNode args, long timeoutMs) {
        requireClient();
        String callId = "svc-" + idSequence.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingServices.put(callId, future);
        // 超时后 completeService 永远不会来，必须自清理，避免注册表泄漏
        future.whenComplete((ignored, error) -> pendingServices.remove(callId));
        future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        client.send(buildCallServiceOp(callId, service, args));
        return future;
    }

    static ObjectNode buildCallServiceOp(String callId, String service, JsonNode args) {
        ObjectNode operation = MAPPER.createObjectNode();
        operation.put("op", "call_service");
        operation.put("id", callId);
        operation.put("service", service);
        operation.set("args", args == null ? MAPPER.createObjectNode() : args);
        return operation;
    }

    public CompletableFuture<ListMapsResult> listMaps() {
        return callService(props.getListMapsService(), null)
                .thenApply(values -> {
                    List<String> names = new ArrayList<>();
                    JsonNode mapNames = values.path("map_names");
                    if (mapNames.isArray()) {
                        mapNames.forEach(name -> names.add(name.asText()));
                    }
                    return new ListMapsResult(
                            values.path("success").asBoolean(false),
                            values.path("message").asText(""),
                            List.copyOf(names));
                });
    }

    public CompletableFuture<GetMapResult> getMap(String mapName) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("map_name", mapName == null ? "" : mapName);
        return callService(props.getGetMapService(), args, Math.max(props.getServiceTimeoutMs(), 30_000L))
                .thenApply(values -> new GetMapResult(
                        values.path("success").asBoolean(false),
                        values.path("message").asText(""),
                        textOrEmpty(values, "map_name"),
                        values.path("map").isMissingNode() ? null : values.path("map")));
    }

    public CompletableFuture<MapServiceResult> loadMap(String mapName) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("map_name", mapName);
        return callService(props.getLoadMapService(), args)
                .thenApply(values -> new MapServiceResult(
                        values.path("success").asBoolean(false),
                        values.path("message").asText(""),
                        textOrEmpty(values, "map_name"),
                        textOrEmpty(values, "operation_id")));
    }

    public CompletableFuture<MapServiceResult> startMapping() {
        return callService(props.getStartMappingService(), null)
                .thenApply(values -> new MapServiceResult(
                        values.path("success").asBoolean(false),
                        values.path("message").asText(""),
                        null,
                        ""));
    }

    public CompletableFuture<MapServiceResult> saveMap(String mapName) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("map_name", mapName);
        return callService(props.getSaveMapService(), args)
                .thenApply(values -> new MapServiceResult(
                        values.path("success").asBoolean(false),
                        values.path("message").asText(""),
                        textOrEmpty(values, "map_name"),
                        ""));
    }

    public CompletableFuture<MapServiceResult> cancelMapping() {
        return callService(props.getCancelMappingService(), null)
                .thenApply(values -> new MapServiceResult(
                        values.path("success").asBoolean(false),
                        values.path("message").asText(""),
                        textOrEmpty(values, "map_name"),
                        textOrEmpty(values, "operation_id")));
    }

    private static String textOrEmpty(JsonNode values, String field) {
        JsonNode value = values.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    public CompletableFuture<RelocalizeResult> callRelocalize(String mapName, double x, double y, double yaw) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("map_name", mapName == null ? "" : mapName);
        args.put("x", x);
        args.put("y", y);
        args.put("yaw", yaw);
        return callService(props.getRelocalizeService(), args, 90_000L)
                .thenApply(values -> new RelocalizeResult(
                        values.path("success").asBoolean(false),
                        values.path("message").asText(""),
                        textOrEmpty(values, "map_name"),
                        textOrEmpty(values, "operation_id")));
    }

    public CompletableFuture<Optional<LocalizationOperation>> queryLocalizationOperation(String operationId) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("operation_id", operationId == null ? "" : operationId);
        return callService(props.getGetLocalizationOperationService(), args)
                .thenApply(values -> {
                    if (!values.path("found").asBoolean(false)) {
                        return Optional.<LocalizationOperation>empty();
                    }
                    return Optional.ofNullable(
                            LocalizationOperation.fromMessage(values.path("operation")));
                })
                .exceptionally(throwable -> {
                    log.warn("get_localization_operation({}) failed: {}", operationId, throwable.getMessage());
                    return Optional.empty();
                });
    }

    public void publishVelocity(double linearX, double linearY, double angularZ) {
        requireClient();
        client.send(buildVelocityOp(props.getCmdVelTopic(), linearX, linearY, angularZ));
    }

    static ObjectNode buildVelocityOp(String topic, double linearX, double linearY, double angularZ) {
        ObjectNode operation = MAPPER.createObjectNode();
        operation.put("op", "publish");
        operation.put("topic", topic);
        ObjectNode msg = operation.putObject("msg");
        msg.putObject("linear").put("x", linearX).put("y", linearY).put("z", 0.0);
        msg.putObject("angular").put("x", 0.0).put("y", 0.0).put("z", angularZ);
        return operation;
    }

    @Override
    public void onMessage(JsonNode message) {
        String op = message.path("op").asText("");
        switch (op) {
            case "action_feedback" -> dispatchFeedback(message);
            case "action_result" -> dispatchResult(message);
            case "service_response" -> completeService(message);
            default -> {
            }
        }
    }

    private void dispatchFeedback(JsonNode message) {
        ActionListener listener = actionListeners.get(message.path("id").asText(""));
        if (listener != null) {
            listener.onFeedback(message.path("values"));
        }
    }

    private void dispatchResult(JsonNode message) {
        String goalId = message.path("id").asText("");
        ActionListener listener = actionListeners.remove(goalId);
        if (listener == null) {
            return;
        }
        JsonNode values = message.path("values");
        // Older rosbridge versions wrap GoalStatus and the action result in values.
        JsonNode status = message.has("status") ? message.path("status") : values.path("status");
        JsonNode result = values.has("result") ? values.path("result") : values;
        String detail = result.isTextual() ? result.asText() : result.path("message").asText("");
        if (detail.isBlank()) {
            detail = message.path("message").asText("");
        }
        int goalStatus = status.asInt(-1);
        if (goalStatus != 4) {
            // Keep rejection/transport errors visible even when WS message logging is disabled.
            log.warn("follow_edge result: goal={}, payload={}", goalId, message);
            if (detail.isBlank()) {
                detail = "机器人未正常完成导航，请检查机器人 action 服务（result="
                        + message.path("result") + "，status=" + status + "）";
            }
        }
        listener.onResult(
                goalStatus,
                result.path("success").asBoolean(false),
                detail,
                result.path("command_id").asText(""));
    }

    private void completeService(JsonNode message) {
        CompletableFuture<JsonNode> future = pendingServices.remove(message.path("id").asText(""));
        JsonNode values = message.path("values");
        // 机器人业务拒绝（参数/地图/忙碌等）：即使消息高亮日志关闭也必须可见——
        // 这是「请求发出去了但被拒」的唯一线索，漏了就只剩 future 超时
        if (values != null && values.has("success") && !values.path("success").asBoolean(true)) {
            log.warn("service {} rejected: {}", message.path("service").asText(""),
                    values.path("message").asText("(no message)"));
        }
        if (future != null) {
            future.complete(values);
        }
    }

    private void requireClient() {
        if (client == null) {
            throw new IllegalStateException("rosbridge 客户端尚未装配（sim-agv.enabled=false？）");
        }
    }

    static ObjectNode buildFollowEdgeOp(String goalId, String action, String actionType, FollowEdgeGoal goal) {
        ObjectNode operation = MAPPER.createObjectNode();
        operation.put("op", "send_action_goal");
        operation.put("id", goalId);
        operation.put("action", action);
        operation.put("action_type", actionType);
        operation.put("feedback", true);
        ObjectNode args = operation.putObject("args");
        args.put("command_id", goal.commandId());
        args.put("node_id", goal.nodeId() == null ? "" : goal.nodeId());
        args.put("x", goal.x());
        args.put("y", goal.y());
        args.put("theta", goal.theta());
        args.put("edge_id", goal.edgeId() == null ? "" : goal.edgeId());
        args.put("edge_type", goal.edgeType() == null ? "STRAIGHT" : goal.edgeType());
        args.put("source_id", goal.sourceId() == null ? "" : goal.sourceId());
        args.put("target_id", goal.targetId() == null ? "" : goal.targetId());
        args.put("max_speed", goal.maxSpeed());
        args.put("back_up", goal.backUp());
        args.put("reverse", goal.reverse());
        args.put("step", goal.step());
        args.put("end_point", goal.endPoint());
        ArrayNode controlPoints = args.putArray("control_points");
        for (ControlPoint point : goal.controlPoints()) {
            controlPoints.addObject().put("x", point.x()).put("y", point.y());
        }
        return operation;
    }

    public record FollowEdgeGoal(
            String commandId,
            String nodeId,
            double x,
            double y,
            double theta,
            String edgeId,
            String edgeType,
            String sourceId,
            String targetId,
            double maxSpeed,
            boolean backUp,
            boolean reverse,
            double step,
            boolean endPoint,
            List<ControlPoint> controlPoints
    ) {
    }

    public record ControlPoint(double x, double y) {
    }

    public interface ActionListener {

        void onFeedback(JsonNode values);

        void onResult(int status, boolean success, String message, String commandId);
    }

    public record SetControlResult(boolean success, String message, String state) {
    }

    public record RelocalizeResult(boolean success, String message, String mapName, String operationId) {
    }

    public record ListMapsResult(boolean success, String message, List<String> mapNames) {
    }

    public record GetMapResult(boolean success, String message, String mapName, JsonNode grid) {
    }

    public record MapServiceResult(boolean success, String message, String mapName, String operationId) {
    }
}
