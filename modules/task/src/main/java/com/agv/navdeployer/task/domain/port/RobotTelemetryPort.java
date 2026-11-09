package com.agv.navdeployer.task.domain.port;

/**
 * 机器人遥测读取端口：状态/位姿/里程计/机械臂。
 */
public interface RobotTelemetryPort {

    RobotStatus status();

    RobotPose mapPose();

    boolean statusFresh();

    long statusAgeMs();

    double odomLinearX();

    double odomLinearY();

    double odomAngularZ();

    long odomCount();

    boolean armMovementAllowed();

    String armState();
}
