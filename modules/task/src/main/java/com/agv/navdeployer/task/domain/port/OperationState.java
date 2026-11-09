package com.agv.navdeployer.task.domain.port;

/**
 * 机器人定位操作（切图/重定位/放弃建图）的当前状态查询结果。
 */
public record OperationState(String operationId,
                             boolean terminal,
                             boolean succeeded,
                             String stage,
                             String message,
                             String failureReason) {

    public static final String STATE_SUCCEEDED = "SUCCEEDED";
}
