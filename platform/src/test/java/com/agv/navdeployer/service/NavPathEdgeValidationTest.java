package com.agv.navdeployer.service;

import com.agv.navdeployer.dto.ControlPointDTO;
import com.agv.navdeployer.dto.PathEdgeDTO;
import com.agv.navdeployer.entity.NavPoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 路线边序列静态校验测试（同图 / 连续性 / 曲线控制点数量）。
 */
class NavPathEdgeValidationTest {

    private NavPoint point(long id) {
        NavPoint point = new NavPoint();
        point.setId(id);
        point.setPointCode("P" + id);
        return point;
    }

    private PathEdgeDTO edge(long source, long target, String type, List<ControlPointDTO> cps) {
        return new PathEdgeDTO(source, target, type, cps, 0.6, false, false);
    }

    @Test
    void acceptsContinuousStraightAndCurveSequence() {
        Map<Long, NavPoint> points = Map.of(1L, point(1), 2L, point(2), 3L, point(3));
        List<PathEdgeDTO> edges = List.of(
                edge(1, 2, "STRAIGHT", null),
                edge(2, 3, "CURVE", List.of(new ControlPointDTO(1.0, 1.0))));
        NavPathService.validateEdgeSequence(edges, points);
    }

    @Test
    void rejectsDiscontinuousSequence() {
        Map<Long, NavPoint> points = Map.of(1L, point(1), 2L, point(2), 3L, point(3));
        List<PathEdgeDTO> edges = List.of(
                edge(1, 2, "STRAIGHT", null),
                edge(1, 3, "STRAIGHT", null));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NavPathService.validateEdgeSequence(edges, points));
        assertEquals(true, e.getMessage().contains("不连续"));
    }

    @Test
    void rejectsPointFromOtherMap() {
        Map<Long, NavPoint> points = Map.of(1L, point(1));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NavPathService.validateEdgeSequence(
                        List.of(edge(1, 99, "STRAIGHT", null)), points));
        assertEquals(true, e.getMessage().contains("不属于该地图"));
    }

    @Test
    void rejectsSelfLoop() {
        Map<Long, NavPoint> points = Map.of(1L, point(1));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NavPathService.validateEdgeSequence(
                        List.of(edge(1, 1, "STRAIGHT", null)), points));
        assertEquals(true, e.getMessage().contains("同一点位"));
    }

    @Test
    void rejectsCurveWithoutEnoughControlPoints() {
        Map<Long, NavPoint> points = Map.of(1L, point(1), 2L, point(2));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NavPathService.validateEdgeSequence(
                        List.of(edge(1, 2, "CURVE", null)), points));
        assertEquals(true, e.getMessage().contains("控制点必须 1~2 个"));
    }

    @Test
    void rejectsCurveWithTooManyControlPoints() {
        Map<Long, NavPoint> points = Map.of(1L, point(1), 2L, point(2));
        List<ControlPointDTO> three = List.of(
                new ControlPointDTO(0.0, 0.0),
                new ControlPointDTO(1.0, 1.0),
                new ControlPointDTO(2.0, 2.0));
        assertThrows(IllegalArgumentException.class,
                () -> NavPathService.validateEdgeSequence(
                        List.of(edge(1, 2, "CURVE", three)), points));
    }

    @Test
    void rejectsStraightWithControlPoints() {
        Map<Long, NavPoint> points = Map.of(1L, point(1), 2L, point(2));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NavPathService.validateEdgeSequence(
                        List.of(edge(1, 2, "STRAIGHT", List.of(new ControlPointDTO(0.0, 0.0)))), points));
        assertEquals(true, e.getMessage().contains("不能配置控制点"));
    }
}
