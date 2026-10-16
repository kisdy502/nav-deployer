package com.agv.navdeployer.service;

import com.agv.navdeployer.sim.RosCommandDispatcher.ControlPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FOLLOW_PATH 反向执行计划测试：段序倒排、端点互换、控制点倒序（同一条曲线反向遍历）。
 */
class MoveTaskReversePathTest {

    private static MoveTaskService.SegmentPlan seg(String srcCode, String dstCode,
                                                   double sx, double sy, double syaw,
                                                   double tx, double ty, double tyaw,
                                                   List<ControlPoint> cps) {
        return new MoveTaskService.SegmentPlan(srcCode, dstCode, tx, ty, tyaw, "CURVE", cps,
                null, false, false, sx, sy, syaw);
    }

    @Test
    void reversesSegmentOrderAndSwapsEndpoints() {
        // 路线 A(0,0)→B(10,0)→C(10,10)，反向后应为 C→B→A，各段目标取原源点位姿
        List<MoveTaskService.SegmentPlan> segments = List.of(
                seg("A", "B", 0, 0, 0.1, 10, 0, 0.2, List.of()),
                seg("B", "C", 10, 0, 0.2, 10, 10, 0.3, List.of()));
        List<MoveTaskService.SegmentPlan> reversed = MoveTaskService.reversePathPlan(segments);

        assertEquals(2, reversed.size());
        MoveTaskService.SegmentPlan first = reversed.get(0);
        assertEquals("C", first.sourceCode());
        assertEquals("B", first.targetCode());
        assertEquals(10, first.x(), 1e-9);
        assertEquals(0, first.y(), 1e-9);
        assertEquals(0.2, first.theta(), 1e-9);

        MoveTaskService.SegmentPlan second = reversed.get(1);
        assertEquals("B", second.sourceCode());
        assertEquals("A", second.targetCode());
        assertEquals(0, second.x(), 1e-9);
        assertEquals(0, second.y(), 1e-9);
        assertEquals(0.1, second.theta(), 1e-9);
    }

    @Test
    void reversesControlPointOrderButNotReverseFlag() {
        // 三次贝塞尔控制点 [c1,c2] 倒序为 [c2,c1]；reverse 固件标志置 false（已自行倒转）
        List<ControlPoint> cps = List.of(new ControlPoint(1, 2), new ControlPoint(3, 4));
        List<MoveTaskService.SegmentPlan> reversed = MoveTaskService.reversePathPlan(List.of(
                seg("A", "B", 0, 0, 0, 10, 0, 0, cps)));
        assertEquals(2, reversed.get(0).controlPoints().size());
        assertEquals(3, reversed.get(0).controlPoints().get(0).x(), 1e-9);
        assertEquals(4, reversed.get(0).controlPoints().get(0).y(), 1e-9);
        assertFalse(reversed.get(0).reverse());
    }

    @Test
    void nearestEndDecision() {
        // 两段路线 (0,0)→(10,0)→(10,10)
        List<MoveTaskService.SegmentPlan> segments = List.of(
                seg("A", "B", 0, 0, 0, 10, 0, 0, List.of()),
                seg("B", "C", 10, 0, 0, 10, 10, 0, List.of()));
        assertTrue(MoveTaskService.shouldReverseForNearestEnd(segments, 9.8, 9.9));
        assertFalse(MoveTaskService.shouldReverseForNearestEnd(segments, 0.1, 0.2));
        // 距两端等距（非严格更近）时保持正向
        assertFalse(MoveTaskService.shouldReverseForNearestEnd(segments, 5.0, 5.0));
    }
}
