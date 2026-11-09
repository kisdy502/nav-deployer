package com.agv.navdeployer.task.domain.port;

/**
 * 任务事件出口（SSE 推送）。
 */
public interface RobotEventPort {

    void publish(String event, Object payload);
}
