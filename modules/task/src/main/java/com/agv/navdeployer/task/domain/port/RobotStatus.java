package com.agv.navdeployer.task.domain.port;

/**
 * 机器人当前状态快照（task 域所需字段的最小投影）。
 */
public record RobotStatus(String mode,
                          String mapName,
                          boolean poseInitialized,
                          Double battery,
                          Boolean charging) {
}
