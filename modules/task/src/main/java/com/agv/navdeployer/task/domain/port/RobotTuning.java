package com.agv.navdeployer.task.domain.port;

/**
 * 机器人遥测调参快照（由基础设施从机器人网关配置构造，启动期固定）。
 */
public record RobotTuning(long statusFreshMs,
                          long modeTaskTimeoutMs,
                          long localizationTaskTimeoutMs,
                          long goalTimeoutSeconds,
                          double defaultMaxSpeed,
                          double defaultStep,
                          double followPathStartRadius) {
}
