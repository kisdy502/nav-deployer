package com.agv.navdeployer.service;

import com.agv.navdeployer.entity.MoveTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * follow_edge GoalStatus → move_task 终态映射测试。
 * status 枚举见 rosbridge_integration.md §2.2：4=SUCCEEDED 5=CANCELED 6=ABORTED。
 */
class MoveTaskStatusMappingTest {

    /** GoalStatus 4/5/6 分别映射为成功/取消/失败终态 */
    @Test
    void mapTerminalStatuses() {
        assertEquals(MoveTask.STATUS_SUCCEEDED,
                MoveTaskService.terminalStatus(4, false, false));
        assertEquals(MoveTask.STATUS_CANCELLED,
                MoveTaskService.terminalStatus(5, true, false));
        assertEquals(MoveTask.STATUS_FAILED,
                MoveTaskService.terminalStatus(6, false, false));
    }

    /** 看门狗超时先取消后收到的 CANCELED 应记为 TIMEOUT 而非普通取消 */
    @Test
    void timeoutWinsOverPlainCancel() {
        // 看门狗先取消、超时后收到的 CANCELED 应记为 TIMEOUT
        assertEquals(MoveTask.STATUS_TIMEOUT,
                MoveTaskService.terminalStatus(5, true, true));
    }

    /** 未知或非终态 status 一律兜底映射为失败 */
    @Test
    void unknownStatusFallsBackToFailed() {
        assertEquals(MoveTask.STATUS_FAILED,
                MoveTaskService.terminalStatus(-1, false, false));
        assertEquals(MoveTask.STATUS_FAILED,
                MoveTaskService.terminalStatus(1, false, false));
    }
}
