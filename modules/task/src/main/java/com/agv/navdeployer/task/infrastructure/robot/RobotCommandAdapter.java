package com.agv.navdeployer.task.infrastructure.robot;

import com.agv.navdeployer.common.robot.RobotGrid;
import com.agv.navdeployer.robot.infrastructure.LiveMapCache;
import com.agv.navdeployer.robot.infrastructure.RosCommandDispatcher;
import com.agv.navdeployer.task.domain.port.FollowEdgeCommand;
import com.agv.navdeployer.task.domain.port.FollowEdgeListener;
import com.agv.navdeployer.task.domain.port.MapCmdResult;
import com.agv.navdeployer.task.domain.port.RobotCommandPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
public class RobotCommandAdapter implements RobotCommandPort {

    private final RosCommandDispatcher dispatcher;

    public RobotCommandAdapter(RosCommandDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public String dispatchFollowEdge(FollowEdgeCommand command, FollowEdgeListener listener) {
        List<RosCommandDispatcher.ControlPoint> controlPoints = command.controlPoints() == null ? List.of()
                : command.controlPoints().stream()
                .map(point -> new RosCommandDispatcher.ControlPoint(point.x(), point.y()))
                .toList();
        RosCommandDispatcher.FollowEdgeGoal goal = new RosCommandDispatcher.FollowEdgeGoal(
                command.commandId(),
                command.targetCode(),
                command.x(), command.y(), command.theta(),
                "edge-" + command.commandId(),
                command.edgeType(),
                command.sourceCode(),
                command.targetCode(),
                command.maxSpeed(),
                command.backUp(),
                command.reverse(),
                command.step(),
                command.endPoint(),
                controlPoints);
        return dispatcher.dispatchFollowEdge(goal, new RosCommandDispatcher.ActionListener() {
            @Override
            public void onFeedback(JsonNode values) {
                listener.onFeedback(new FollowEdgeListener.FollowFeedback(
                        values.path("x").isNumber() ? values.path("x").asDouble() : null,
                        values.path("y").isNumber() ? values.path("y").asDouble() : null,
                        values.path("theta").isNumber() ? values.path("theta").asDouble() : null,
                        values.path("state").isTextual() ? values.path("state").asText() : null));
            }

            @Override
            public void onResult(int status, boolean success, String message, String commandId) {
                listener.onResult(status, success, message, commandId);
            }
        });
    }

    @Override
    public void cancelGoal(String goalId) {
        dispatcher.cancelGoal(goalId);
    }

    @Override
    public CompletableFuture<MapCmdResult> loadMap(String mapName) {
        return dispatcher.loadMap(mapName).thenApply(RobotCommandAdapter::toCmdResult);
    }

    @Override
    public CompletableFuture<MapCmdResult> startMapping() {
        return dispatcher.startMapping().thenApply(RobotCommandAdapter::toCmdResult);
    }

    @Override
    public CompletableFuture<MapCmdResult> saveMap(String mapName) {
        return dispatcher.saveMap(mapName).thenApply(RobotCommandAdapter::toCmdResult);
    }

    @Override
    public CompletableFuture<MapCmdResult> cancelMapping() {
        return dispatcher.cancelMapping().thenApply(RobotCommandAdapter::toCmdResult);
    }

    @Override
    public RobotGrid getMap(String mapName) {
        RosCommandDispatcher.GetMapResult result = dispatcher.getMap(mapName).join();
        if (!result.success() || result.grid() == null) {
            throw new IllegalStateException("get_map 失败: "
                    + (result.message() == null || result.message().isBlank()
                            ? "无栅格数据" : result.message()));
        }
        LiveMapCache.OccupancyGrid grid = LiveMapCache.parse(result.grid());
        if (grid == null) {
            throw new IllegalStateException("get_map 返回的栅格数据非法（几何不一致）");
        }
        return new RobotGrid(grid.frameId(), grid.resolution(), grid.width(), grid.height(),
                grid.originX(), grid.originY(), grid.originYaw(), grid.data(), Instant.now());
    }

    @Override
    public CompletableFuture<RobotMapList> listMaps() {
        return dispatcher.listMaps().thenApply(result -> new RobotMapList(
                result.success(), result.message(), result.mapNames()));
    }

    @Override
    public CompletableFuture<MapCmdResult> relocalize(String mapName, double x, double y, double yaw) {
        return dispatcher.callRelocalize(mapName, x, y, yaw).thenApply(result -> new MapCmdResult(
                result.success(), result.message(), result.mapName(), result.operationId()));
    }

    @Override
    public CompletableFuture<JsonNode> callService(String service, ObjectNode args, long timeoutMs) {
        return dispatcher.callService(service, args, timeoutMs);
    }

    private static MapCmdResult toCmdResult(RosCommandDispatcher.MapServiceResult result) {
        return new MapCmdResult(result.success(), result.message(), result.mapName(), result.operationId());
    }
}
