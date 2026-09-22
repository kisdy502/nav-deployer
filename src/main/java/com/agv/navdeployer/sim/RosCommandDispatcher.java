package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * rosbridge 下行通道：follow_edge action 下发/取消、set_control 服务调用、initialpose 发布。
 *
 * 关联模型：dispatchFollowEdge 返回 goalId，后续 action_feedback / action_result
 * 都携带同一 id，分派给注册的 listener；service_response 按调用 id 关联 Future。
 * 本类运行回调在 WS IO 线程，listener 必须快速返回（MoveTaskService 会转交业务线程）。
 */
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

    /** 由装配方在 RosbridgeClient 创建后回填（避免构造环）。 */
    public void attach(RosbridgeClient client) {
        this.client = client;
    }

    /** 下发一条 follow_edge goal。goal 字段见 rosbridge_integration.md §5.1。 */
    public String dispatchFollowEdge(FollowEdgeGoal goal, ActionListener listener) {
        requireClient();
        String goalId = "goal-" + idSequence.incrementAndGet();
        actionListeners.put(goalId, listener);
        client.send(buildFollowEdgeOp(goalId, props.getFollowEdgeAction(), props.getFollowEdgeActionType(), goal));
        return goalId;
    }

    /** 取消一条在途 goal（cancel_action_goal 的 id 必须与发送时完全一致）。 */
    public void cancelGoal(String goalId) {
        requireClient();
        ObjectNode operation = MAPPER.createObjectNode();
        operation.put("op", "cancel_action_goal");
        operation.put("id", goalId);
        operation.put("action", props.getFollowEdgeAction());
        client.send(operation);
    }

    /** 调用 /agv/set_control（start/stop/reset），同步等待 service_response，超时抛异常。 */
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

    /**
     * 通用服务调用：任意 rosbridge service，args 为 null 时传空对象。
     * 响应超时（service_timeout_ms）后 future 异常完成，注册表条目随之清理。
     */
    public CompletableFuture<JsonNode> callService(String service, JsonNode args) {
        return callService(service, args, props.getServiceTimeoutMs());
    }

    /** 指定超时版本：大响应服务（如 get_map 栅格数 MB）需要更长等待。 */
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

    /** 构建 call_service op（独立成方法便于单测校验 JSON 契约）。 */
    static ObjectNode buildCallServiceOp(String callId, String service, JsonNode args) {
        ObjectNode operation = MAPPER.createObjectNode();
        operation.put("op", "call_service");
        operation.put("id", callId);
        operation.put("service", service);
        operation.set("args", args == null ? MAPPER.createObjectNode() : args);
        return operation;
    }

    // ==================== 地图管理服务（agv_bridge_v2 v0.3.0+）====================

    /** /agv/list_maps：机器人 maps_dir 下可导入的地图名（pgm+yaml 齐全）。 */
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

    /**
     * /agv/get_map：拉取地图栅格（OccupancyGrid，msg 结构含 header/info/data）。
     * 数据量可达数 MB，超时取 max(service_timeout_ms, 30s)；仅用于入库/展示，勿放 WS IO 线程处理。
     */
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

    /** /agv/load_map：切图（受理即返回，完成看 /agv/status.mode: RELOCALIZING -> NAVIGATION）。 */
    public CompletableFuture<MapServiceResult> loadMap(String mapName) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("map_name", mapName);
        return callService(props.getLoadMapService(), args)
                .thenApply(values -> new MapServiceResult(
                        values.path("success").asBoolean(false),
                        values.path("message").asText(""),
                        textOrEmpty(values, "map_name")));
    }

    /** /agv/start_mapping：进入建图（受理即返回，mode -> MAPPING）。 */
    public CompletableFuture<MapServiceResult> startMapping() {
        return callService(props.getStartMappingService(), null)
                .thenApply(values -> new MapServiceResult(
                        values.path("success").asBoolean(false),
                        values.path("message").asText(""),
                        null));
    }

    /** /agv/save_map：保存建图并回定位（受理即返回，MAPPING -> RELOCALIZING -> NAVIGATION）。 */
    public CompletableFuture<MapServiceResult> saveMap(String mapName) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("map_name", mapName);
        return callService(props.getSaveMapService(), args)
                .thenApply(values -> new MapServiceResult(
                        values.path("success").asBoolean(false),
                        values.path("message").asText(""),
                        textOrEmpty(values, "map_name")));
    }

    private static String textOrEmpty(JsonNode values, String field) {
        JsonNode value = values.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    /** 发布重定位位姿（PoseWithCovarianceStamped，仅 x/y/yaw 协方差有效）。 */
    public void publishInitialPose(double x, double y, double theta) {
        requireClient();
        ObjectNode operation = MAPPER.createObjectNode();
        operation.put("op", "publish");
        operation.put("topic", props.getInitialPoseTopic());
        ObjectNode msg = operation.putObject("msg");
        ObjectNode header = msg.putObject("header");
        header.put("stamp", "now");
        header.put("frame_id", "map");
        ObjectNode poseWrapper = msg.putObject("pose");
        ObjectNode pose = poseWrapper.putObject("pose");
        pose.putObject("position").put("x", x).put("y", y).put("z", 0.0);
        pose.putObject("orientation")
                .put("x", 0.0).put("y", 0.0)
                .put("z", Math.sin(theta / 2.0))
                .put("w", Math.cos(theta / 2.0));
        ArrayNode covariance = poseWrapper.putArray("covariance");
        for (int i = 0; i < 36; i++) {
            covariance.add(0.0);
        }
        covariance.set(0, DoubleNode.valueOf(0.25));          // x
        covariance.set(7, DoubleNode.valueOf(0.25));          // y
        covariance.set(35, DoubleNode.valueOf(0.0685));       // yaw
        client.send(operation);
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
        listener.onResult(
                message.path("status").asInt(-1),
                values.path("success").asBoolean(false),
                values.path("message").asText(""),
                values.path("command_id").asText(""));
    }

    private void completeService(JsonNode message) {
        CompletableFuture<JsonNode> future = pendingServices.remove(message.path("id").asText(""));
        if (future != null) {
            future.complete(message.path("values"));
        }
    }

    private void requireClient() {
        if (client == null) {
            throw new IllegalStateException("rosbridge 客户端尚未装配（sim-agv.enabled=false？）");
        }
    }

    /** 构建 send_action_goal op（独立成方法便于单测校验 JSON 契约）。 */
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

    /** 单条 follow_edge goal 的完整字段（对齐 agv_bridge_v2_interfaces/action/FollowEdge）。 */
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

    /** goal 生命周期回调。 */
    public interface ActionListener {

        /** 400ms 一次的执行反馈：x/y/theta/state。 */
        void onFeedback(JsonNode values);

        /** 终态：status=4 成功 / 5 取消 / 6 失败（见 rosbridge_integration.md §2.2）。 */
        void onResult(int status, boolean success, String message, String commandId);
    }

    /** set_control 服务响应。 */
    public record SetControlResult(boolean success, String message, String state) {
    }

    /** /agv/list_maps 服务响应。 */
    public record ListMapsResult(boolean success, String message, List<String> mapNames) {
    }

    /** /agv/get_map 服务响应：grid 为 OccupancyGrid msg 节点（header/info/data）。 */
    public record GetMapResult(boolean success, String message, String mapName, JsonNode grid) {
    }

    /** 地图模式类服务（load_map/start_mapping/save_map）响应：受理结果，完成靠 mode 流转。 */
    public record MapServiceResult(boolean success, String message, String mapName) {
    }
}
