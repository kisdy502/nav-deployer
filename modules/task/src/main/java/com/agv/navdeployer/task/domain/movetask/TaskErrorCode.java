package com.agv.navdeployer.task.domain.movetask;

import com.agv.navdeployer.common.error.ErrorCode;

public enum TaskErrorCode implements ErrorCode {

    MOVE_TASK_NOT_FOUND(404, "移动任务不存在"),
    MOVE_TASK_NOT_RUNNING(400, "任务不在执行中，无法取消"),
    WAYPOINT_NOT_FOUND(404, "点位不存在"),
    PATH_NOT_FOUND(404, "路线不存在");

    private final int status;
    private final String message;

    TaskErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }
}
