package com.agv.navdeployer.task.domain.port;

/**
 * follow_edge 执行回调（反馈/终态）。
 */
public interface FollowEdgeListener {

    void onFeedback(FollowFeedback feedback);

    void onResult(int goalStatus, boolean success, String message, String commandId);

    record FollowFeedback(Double x, Double y, Double theta, String state) {
    }
}
