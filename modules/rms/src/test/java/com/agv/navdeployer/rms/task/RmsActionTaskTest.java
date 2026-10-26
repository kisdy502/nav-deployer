package com.agv.navdeployer.rms.task;

import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.protocol.dto.command.BodyReply;
import com.agv.navdeployer.rms.protocol.dto.command.TaskCommandRequest;
import com.agv.navdeployer.rms.protocol.dto.report.TaskResultEnvelope;
import com.agv.navdeployer.rms.state.RobotStateView;
import com.agv.navdeployer.service.MoveTaskService;
import com.agv.navdeployer.service.RobotOperationalService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 行为树 action 任务（fetch_cable 等）协议不携带 parameters.destination
 * （见《嵌入式系统软件接口详细设计-云平台接口》task/add：destination 仅
 * task_type=navigate 有效；RMS startActivity 下发的 parameters 恒为空），
 * 上位机须按占位 action 执行而非判 destination 缺失失败。
 */
class RmsActionTaskTest {

    private RmsTaskService newService(RmsResultReporter reporter, MoveTaskService moves) {
        return new RmsTaskService(new RmsTaskRegistry(), reporter,
                mock(RobotStateView.class), moves, new RmsProperties(),
                null, null, null, null, null, null, null, mock(RobotOperationalService.class));
    }

    @Test
    void actionTaskWithoutDestinationCompletesInPlace() throws Exception {
        RmsProperties properties = new RmsProperties();
        properties.getBehavior().setActionDurationMs(0);
        RmsResultReporter reporter = new RmsResultReporter(properties);
        AtomicReference<TaskResultEnvelope> result = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        reporter.attach((envelope, id) -> { result.set(envelope); reported.countDown(); });
        MoveTaskService moves = mock(MoveTaskService.class);
        RmsTaskService service = new RmsTaskService(new RmsTaskRegistry(), reporter,
                mock(RobotStateView.class), moves, properties,
                null, null, null, null, null, null, null, mock(RobotOperationalService.class));
        try {
            TaskCommandRequest request = new TaskCommandRequest(
                    "tf-240354505530093568-240354505664311296", "act-1", null,
                    "action", "fetch_cable", null, null, Map.of());
            assertThat(service.addTask(request).status()).isZero();
            BodyReply started = service.startTask(request);
            assertThat(started.status()).as("start reply: %s", started.msg()).isZero();
            assertThat(reported.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get().data().actionType()).isEqualTo("action");
            assertThat(result.get().data().actionStatus()).isEqualTo(3);
            assertThat(result.get().data().resultReport()).isNull();
            verify(moves, never()).create(any());
        } finally {
            shutdown(service);
        }
    }

    @Test
    void navigateTaskWithoutDestinationStillFailsFast() throws Exception {
        RmsResultReporter reporter = new RmsResultReporter(new RmsProperties());
        AtomicReference<TaskResultEnvelope> result = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        reporter.attach((envelope, id) -> { result.set(envelope); reported.countDown(); });
        MoveTaskService moves = mock(MoveTaskService.class);
        RmsTaskService service = newService(reporter, moves);
        try {
            TaskCommandRequest request = new TaskCommandRequest(
                    "nav-no-dest-1", "nav-1", null,
                    "navigate", "rmf_navigate", null, null, Map.of());
            service.addTask(request);
            assertThat(service.startTask(request).status()).isZero();
            assertThat(reported.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get().data().actionStatus()).isEqualTo(13);
            assertThat(result.get().data().msg()).contains("no destination");
            verify(moves, never()).create(any());
        } finally {
            shutdown(service);
        }
    }

    private static void shutdown(RmsTaskService service) throws Exception {
        var worker = RmsTaskService.class.getDeclaredField("worker");
        worker.setAccessible(true);
        ((ScheduledExecutorService) worker.get(service)).shutdownNow();
    }
}
