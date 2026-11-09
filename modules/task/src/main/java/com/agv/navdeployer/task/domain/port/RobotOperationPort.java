package com.agv.navdeployer.task.domain.port;

/**
 * 机器人定位操作查询端口（operation_id → 当前状态）。
 */
public interface RobotOperationPort {

    OperationState query(String operationId);

    /**
     * 主动向机器人刷新一次该操作的最新记录。
     */
    void refresh(String operationId);
}
