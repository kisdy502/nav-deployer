package com.agv.navdeployer.task.domain.port;

import com.agv.navdeployer.common.robot.RobotGrid;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.concurrent.CompletableFuture;

/**
 * 机器人指令端口：导航下发、地图/建图指令与通用 ROS service 调用的唯一出口。
 * task 域经此端口驱动机器人，不感知 rosbridge 协议细节。
 */
public interface RobotCommandPort {

    /**
     * @return goalId（用于取消）
     */
    String dispatchFollowEdge(FollowEdgeCommand command, FollowEdgeListener listener);

    void cancelGoal(String goalId);

    CompletableFuture<MapCmdResult> loadMap(String mapName);

    CompletableFuture<MapCmdResult> startMapping();

    CompletableFuture<MapCmdResult> saveMap(String mapName);

    CompletableFuture<MapCmdResult> cancelMapping();

    /**
     * 按名拉取已保存地图的栅格；失败以异常表达（含机器人拒绝原因）。
     */
    RobotGrid getMap(String mapName);

    /**
     * 机器人侧可加载地图清单。
     */
    CompletableFuture<RobotMapList> listMaps();

    CompletableFuture<MapCmdResult> relocalize(String mapName, double x, double y, double yaw);

    /**
     * 通用 ROS service 调用（service 名与超时由调用方域配置决定）。
     */
    CompletableFuture<JsonNode> callService(String service, ObjectNode args, long timeoutMs);

    record RobotMapList(boolean success, String message, java.util.List<String> mapNames) {
    }
}
