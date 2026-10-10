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
    @ValueSource(strings = {"SUCCEEDED", "FAILED", "TIMEOUT"})
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
        AtomicReference<String> moveStatus = new AtomicReference<>("RUNNING");
        when(moves.get(42L)).thenAnswer(invocation -> {
            MoveTaskVO state = new MoveTaskVO();
            state.setStatus(moveStatus.get());
            if (!"SUCCEEDED".equals(moveStatus.get())) {
                state.setErrorMessage("robot navigation " + moveStatus.get());
            }
            return state;
        });
        RmsTaskService service = new RmsTaskService(registry, reporter,
                mock(RobotStateView.class), moves, properties);
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
                    "SUCCEEDED".equals(terminalStatus) ? 3 : 13);
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
}
