package com.agv.navdeployer.rms.task;

import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.protocol.dto.command.BodyReply;
import com.agv.navdeployer.rms.protocol.dto.command.TaskCommandRequest;
import com.agv.navdeployer.rms.state.RobotStateView;
import com.agv.navdeployer.service.MapAlignmentGuard;
import com.agv.navdeployer.service.MoveTaskService;
import com.agv.navdeployer.service.RobotOperationalService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 地图一致性前置闸门：机器人地图 ≠ 导航基准图时 task/start 直接拒绝，
 * 错误消息透传给 RMS（同名点位坐标错位会导致导航到错误位置乱撞）。
 */
class RmsMapGateTest {

    @Test
    void startTaskRejectedOnMapMismatchAndErrorPropagates() throws Exception {
        RmsProperties properties = new RmsProperties();
        RmsTaskRegistry registry = new RmsTaskRegistry();
        RmsResultReporter reporter = new RmsResultReporter(properties);
        MoveTaskService moves = mock(MoveTaskService.class);
        MapAlignmentGuard guard = mock(MapAlignmentGuard.class);
        doThrow(new IllegalStateException(
                "地图不一致：机器人当前加载「map1025L」，上位机导航基准为「map1027S」（v2 已发布地图）"))
                .when(guard).requireAligned();
        RmsTaskService service = new RmsTaskService(registry, reporter,
                mock(RobotStateView.class), moves, properties, null, null, null, null,
                new com.agv.navdeployer.service.SmartNavigationService(moves, null, null, null, null),
                mock(com.agv.navdeployer.service.ChargeService.class),
                mock(RobotOperationalService.class), guard);
        try {
            TaskCommandRequest request = new TaskCommandRequest(
                    "map-mismatch-1", "1", null, "navigate", "rmf_navigate",
                    null, null, Map.of("destination", Map.of("x", 1.0, "y", 2.0, "yaw", 0.0)));
            service.addTask(request);
            BodyReply reply = service.startTask(request);
            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.msg()).contains("地图不一致").contains("map1025L").contains("map1027S");
            // 拒绝后不占闸门、不下发内部移动
            assertThat(registry.busyExcept("next-task")).isFalse();
            verify(moves, never()).create(any());
        } finally {
            var worker = RmsTaskService.class.getDeclaredField("worker");
            worker.setAccessible(true);
            ((ScheduledExecutorService) worker.get(service)).shutdownNow();
        }
    }
}
