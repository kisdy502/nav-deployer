package com.agv.navdeployer.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FOLLOW_PATH 起点纠偏测试：机器人距首段源点超过阈值时插入"驶向起点"段。
 */
class MoveTaskStartHopTest {

    private static MoveTaskService.SegmentPlan seg(double sx, double sy, double tx, double ty) {
        return new MoveTaskService.SegmentPlan("S", "T", tx, ty, 0, "STRAIGHT", List.of(),
                null, false, false, sx, sy, 0);
    }

    @Test
    void prependsHopWhenRobotFarFromStart() {
        List<MoveTaskService.SegmentPlan> segments = List.of(seg(0, 0, 10, 0));
        List<MoveTaskService.SegmentPlan> result =
                MoveTaskService.withStartHopIfNeeded(segments, 20.0, 5.0, 0.5);
        assertEquals(2, result.size());
        MoveTaskService.SegmentPlan hop = result.get(0);
        assertEquals(0, hop.x(), 1e-9);
        assertEquals(0, hop.y(), 1e-9);
        assertEquals(0, hop.theta(), 1e-9);
        assertEquals("S", hop.targetCode());
        assertSame(segments.get(0), result.get(1));
    }

    @Test
    void hopHeadingFollowsFirstEdgeDirection() {
        List<MoveTaskService.SegmentPlan> segments = List.of(seg(0, 0, 0, 10));
        MoveTaskService.SegmentPlan hop =
                MoveTaskService.withStartHopIfNeeded(segments, -5.0, -5.0, 0.5).get(0);
        assertEquals(Math.PI / 2, hop.theta(), 1e-9);
    }

    @Test
    void noHopWhenRobotNearStart() {
        List<MoveTaskService.SegmentPlan> segments = List.of(seg(0, 0, 10, 0));
        assertSame(segments, MoveTaskService.withStartHopIfNeeded(segments, 0.3, 0.0, 0.5));
    }

    @Test
    void noHopWithoutPoseOrSource() {
        List<MoveTaskService.SegmentPlan> segments = List.of(seg(0, 0, 10, 0));
        assertSame(segments, MoveTaskService.withStartHopIfNeeded(segments, null, null, 0.5));
        List<MoveTaskService.SegmentPlan> noSource = List.of(new MoveTaskService.SegmentPlan(
                "", "", 10, 0, 0, "STRAIGHT", List.of(), null, false, false, Double.NaN, Double.NaN, Double.NaN));
        assertSame(noSource, MoveTaskService.withStartHopIfNeeded(noSource, 20.0, 5.0, 0.5));
    }

    @Test
    void reverseWhenRobotCloserToEnd() {
        // 路线 (0,0)→(10,0)，机器人站在终点附近 → 应反向
        List<MoveTaskService.SegmentPlan> segments = List.of(seg(0, 0, 10, 0));
        assertTrue(MoveTaskService.shouldReverseForNearestEnd(segments, 9.5, 0.2));
        // 站在起点附近 → 不反向
        assertFalse(MoveTaskService.shouldReverseForNearestEnd(segments, 0.3, 0.0));
        // 无位姿 / 无源点坐标 → 不反向
        assertFalse(MoveTaskService.shouldReverseForNearestEnd(segments, null, null));
    }
}
