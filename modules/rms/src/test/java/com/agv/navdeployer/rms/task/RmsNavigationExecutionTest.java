package com.agv.navdeployer.rms.task;

import com.agv.navdeployer.dto.MoveTaskCreateDTO;
import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.protocol.dto.command.TaskCommandRequest;
import com.agv.navdeployer.rms.protocol.dto.report.TaskResultEnvelope;
import com.agv.navdeployer.rms.state.RobotStateView;
import com.agv.navdeployer.service.MoveTaskService;
import com.agv.navdeployer.vo.MoveTaskVO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RmsNavigationExecutionTest {
    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED", "TIMEOUT", "CANCELLED"})
    void inspectionWorkflowNavigationWaitsForActualMoveResult(String terminalStatus) throws Exception {
        RmsProperties properties = new RmsProperties();
        properties.getBehavior().setInspectionDurationMs(0);
        RmsTaskRegistry registry = new RmsTaskRegistry();
        RmsResultReporter reporter = new RmsResultReporter(properties);
        AtomicReference<TaskResultEnvelope> result = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        reporter.attach((envelope, id) -> { result.set(envelope); reported.countDown(); });
        MoveTaskService moves = mock(MoveTaskService.class);
        MoveTaskVO created = new MoveTaskVO();
        created.setId(42L);
        when(moves.create(any())).thenReturn(created);
        AtomicReference<String> moveStatus = new AtomicReference<>("EXECUTING");
        when(moves.get(42L)).thenAnswer(invocation -> {
            MoveTaskVO state = new MoveTaskVO();
            state.setStatus(moveStatus.get());
            if (!"SUCCEEDED".equals(moveStatus.get())) {
                state.setErrorMessage("robot navigation " + moveStatus.get());
            }
            return state;
        });
        // SmartNavigationService 传真实实例 + null 路线依赖：tryRouteNavigation 内部异常被
        // 捕获回落 GOAL（与原 RmsTaskService 内联实现行为一致）
        RmsTaskService service = new RmsTaskService(registry, reporter,
                mock(RobotStateView.class), moves, properties, null, null, null, null,
                new com.agv.navdeployer.service.SmartNavigationService(moves, null, null, null, null),
                mock(com.agv.navdeployer.service.ChargeService.class),
                mock(com.agv.navdeployer.service.RobotOperationalService.class),
                null);
        try {
            TaskCommandRequest request = new TaskCommandRequest(
                    "workflow-1", "18", null, "navigate", "quality_inspection",
                    null, null, Map.of("destination", Map.of("x", 2.75, "y", -0.83, "yaw", -0.013)));
            service.addTask(request);
            service.startTask(request);
            var captor = org.mockito.ArgumentCaptor.forClass(MoveTaskCreateDTO.class);
            verify(moves, timeout(3000)).create(captor.capture());
            assertThat(captor.getValue().taskType()).isEqualTo("GOAL");
            assertThat(captor.getValue().x()).isEqualTo(2.75);
            assertThat(captor.getValue().y()).isEqualTo(-0.83);
            assertThat(captor.getValue().theta()).isEqualTo(-0.013);
            assertThat(reported.await(650, TimeUnit.MILLISECONDS)).isFalse();
            moveStatus.set(terminalStatus);
            assertThat(reported.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get().data().actionType()).isEqualTo("navigate");
            assertThat(result.get().data().actionId()).isEqualTo("18");
            assertThat(result.get().data().actionStatus()).isEqualTo(
                    "SUCCEEDED".equals(terminalStatus) ? 3
                            : "CANCELLED".equals(terminalStatus) ? 5 : 13);
            assertThat(result.get().data().resultReport()).isNull();
            if (!"SUCCEEDED".equals(terminalStatus)) {
                assertThat(result.get().data().msg()).isEqualTo("robot navigation " + terminalStatus);
            }
            verify(moves, times(1)).create(any());
        } finally {
            var worker = RmsTaskService.class.getDeclaredField("worker");
            worker.setAccessible(true);
            ((ScheduledExecutorService) worker.get(service)).shutdownNow();
        }
    }

    /** 回归：内部移动被外部取消（如前端手动取消）后，RMS 任务必须收口上报 canceled，不得无限轮询卡 RUNNING。 */
    @org.junit.jupiter.api.Test
    void moveCancelledExternallyMustFinalizeRmsTask() throws Exception {
        RmsProperties properties = new RmsProperties();
        RmsTaskRegistry registry = new RmsTaskRegistry();
        RmsResultReporter reporter = new RmsResultReporter(properties);
        java.util.concurrent.CountDownLatch reported = new java.util.concurrent.CountDownLatch(1);
        java.util.List<TaskResultEnvelope> results = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        reporter.attach((envelope, id) -> { results.add(envelope); reported.countDown(); });
        MoveTaskService moves = mock(MoveTaskService.class);
        MoveTaskVO created = new MoveTaskVO();
        created.setId(43L);
        when(moves.create(any())).thenReturn(created);
        AtomicReference<String> moveStatus = new AtomicReference<>("EXECUTING");
        when(moves.get(43L)).thenAnswer(invocation -> {
            MoveTaskVO state = new MoveTaskVO();
            state.setStatus(moveStatus.get());
            return state;
        });
        RmsTaskService service = new RmsTaskService(registry, reporter,
                mock(RobotStateView.class), moves, properties, null, null, null, null,
                new com.agv.navdeployer.service.SmartNavigationService(moves, null, null, null, null),
                mock(com.agv.navdeployer.service.ChargeService.class),
                mock(com.agv.navdeployer.service.RobotOperationalService.class),
                null);
        try {
            TaskCommandRequest request = new TaskCommandRequest(
                    "workflow-2", "19", null, "navigate", "rmf_navigate",
                    null, null, Map.of("destination", Map.of("x", 1.0, "y", 2.0, "yaw", 0.0)));
            service.addTask(request);
            service.startTask(request);
            verify(moves, timeout(3000)).create(any());
            // 外部取消内部移动（前端手动取消 / 被抢占）后，RMS 任务应收口为 canceled 并上报
            moveStatus.set("CANCELLED");
            assertThat(reported.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(results.get(0).data().actionStatus()).isEqualTo(5);
            // 收口后 busy 闸门必须放行新任务（事故中卡 RUNNING 导致后续 task/start 全部 409）
            assertThat(registry.busyExcept("another-task")).isFalse();
        } finally {
            var worker = RmsTaskService.class.getDeclaredField("worker");
            worker.setAccessible(true);
            ((ScheduledExecutorService) worker.get(service)).shutdownNow();
        }
    }

    /** 兜底：内部移动进入未知非活跃状态时按失败收口，不无限轮询。 */
    @org.junit.jupiter.api.Test
    void moveUnexpectedStatusMustFailFast() throws Exception {
        RmsProperties properties = new RmsProperties();
        RmsTaskRegistry registry = new RmsTaskRegistry();
        RmsResultReporter reporter = new RmsResultReporter(properties);
        java.util.concurrent.CountDownLatch reported = new java.util.concurrent.CountDownLatch(1);
        reporter.attach((envelope, id) -> reported.countDown());
        MoveTaskService moves = mock(MoveTaskService.class);
        MoveTaskVO created = new MoveTaskVO();
        created.setId(44L);
        when(moves.create(any())).thenReturn(created);
        when(moves.get(44L)).thenAnswer(invocation -> {
            MoveTaskVO state = new MoveTaskVO();
            state.setStatus("MYSTERY");
            return state;
        });
        RmsTaskService service = new RmsTaskService(registry, reporter,
                mock(RobotStateView.class), moves, properties, null, null, null, null,
                new com.agv.navdeployer.service.SmartNavigationService(moves, null, null, null, null),
                mock(com.agv.navdeployer.service.ChargeService.class),
                mock(com.agv.navdeployer.service.RobotOperationalService.class),
                null);
        try {
            TaskCommandRequest request = new TaskCommandRequest(
                    "workflow-3", "20", null, "navigate", "rmf_navigate",
                    null, null, Map.of("destination", Map.of("x", 1.0, "y", 2.0, "yaw", 0.0)));
            service.addTask(request);
            service.startTask(request);
            verify(moves, timeout(3000)).create(any());
            assertThat(reported.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.busyExcept("another-task")).isFalse();
        } finally {
            var worker = RmsTaskService.class.getDeclaredField("worker");
            worker.setAccessible(true);
            ((ScheduledExecutorService) worker.get(service)).shutdownNow();
        }
    }

    /** 运维复位：非终态任务强制收口 canceled（取消内部移动 + 上报 RMS + 释放 busy 闸门）。 */
    @org.junit.jupiter.api.Test
    void manualResetFinalizesInFlightTaskAndReleasesBusyGate() throws Exception {
        RmsProperties properties = new RmsProperties();
        RmsTaskRegistry registry = new RmsTaskRegistry();
        RmsResultReporter reporter = new RmsResultReporter(properties);
        java.util.concurrent.CountDownLatch reported = new java.util.concurrent.CountDownLatch(1);
        java.util.List<TaskResultEnvelope> results =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        reporter.attach((envelope, id) -> {
            results.add(envelope);
            reported.countDown();
        });
        MoveTaskService moves = mock(MoveTaskService.class);
        MoveTaskVO created = new MoveTaskVO();
        created.setId(45L);
        when(moves.create(any())).thenReturn(created);
        when(moves.get(45L)).thenAnswer(invocation -> {
            MoveTaskVO state = new MoveTaskVO();
            state.setStatus("EXECUTING");
            return state;
        });
        RmsTaskService service = new RmsTaskService(registry, reporter,
                mock(RobotStateView.class), moves, properties, null, null, null, null,
                new com.agv.navdeployer.service.SmartNavigationService(moves, null, null, null, null),
                mock(com.agv.navdeployer.service.ChargeService.class),
                mock(com.agv.navdeployer.service.RobotOperationalService.class),
                null);
        try {
            TaskCommandRequest request = new TaskCommandRequest(
                    "stuck-1", "21", null, "navigate", "rmf_navigate",
                    null, null, Map.of("destination", Map.of("x", 1.0, "y", 2.0, "yaw", 0.0)));
            service.addTask(request);
            service.startTask(request);
            verify(moves, timeout(3000)).create(any());
            assertThat(registry.busyExcept("new-task")).isTrue();

            java.util.List<RmsTaskVO> reset = service.resetInFlight("人工复位（测试）");
            assertThat(reset).hasSize(1);
            assertThat(reset.get(0).status()).isEqualTo("CANCELED");
            verify(moves).cancel(45L);
            assertThat(reported.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(results.get(0).data().actionStatus()).isEqualTo(5);
            // 复位后 busy 闸门放行 + 无在途任务：RMS 可继续下发
            assertThat(registry.busyExcept("new-task")).isFalse();
            assertThat(service.activeTaskView()).isNull();
        } finally {
            var worker = RmsTaskService.class.getDeclaredField("worker");
            worker.setAccessible(true);
            ((ScheduledExecutorService) worker.get(service)).shutdownNow();
        }
    }
}
