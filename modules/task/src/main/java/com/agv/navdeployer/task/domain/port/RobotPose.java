package com.agv.navdeployer.task.domain.port;

/**
 * 机器人在地图坐标系下的当前位姿。
 */
public record RobotPose(double x, double y, double yaw) {
}
