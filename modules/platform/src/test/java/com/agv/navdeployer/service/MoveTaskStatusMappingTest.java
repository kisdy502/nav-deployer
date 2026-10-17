package com.agv.navdeployer.service;

import com.agv.navdeployer.entity.MoveTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * follow_edge GoalStatus → move_task 终态映射测试。
 * status 枚举见 rosbridge_integration.md §2.2：4=SUCCEEDED 5=CANCELED 6=ABORTED。
 */
class MoveTaskStatusMappingTest {

    @Test
    void mapTerminalStatuses() {
        assertEquals(MoveTask.STATUS_SUCCEEDED,
                MoveTaskService.terminalStatus(4, false, false));
        assertEquals(MoveTask.STATUS_CANCELLED,
                MoveTaskService.terminalStatus(5, true, false));
        assertEquals(MoveTask.STATUS_FAILED,
                MoveTaskService.terminalStatus(6, false, false));
    }

    @Test
    void timeoutWinsOverPlainCancel() {
        // 看门狗先取消、超时后收到的 CANCELED 应记为 TIMEOUT
        assertEquals(MoveTask.STATUS_TIMEOUT,
                MoveTaskService.terminalStatus(5, true, true));
    }

    @Test
    void unknownStatusFallsBackToFailed() {
        assertEquals(MoveTask.STATUS_FAILED,
                MoveTaskService.terminalStatus(-1, false, false));
        assertEquals(MoveTask.STATUS_FAILED,
                MoveTaskService.terminalStatus(1, false, false));
    }
}
