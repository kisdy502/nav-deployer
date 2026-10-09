package com.agv.navdeployer.rms.session;

import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.gateway.RmsCommandGateway;
import com.agv.navdeployer.rms.gateway.RmsReportGateway;
import com.agv.navdeployer.rms.gateway.RmsServiceGateway;
import com.agv.navdeployer.rms.state.RobotStateView;
import com.agv.navdeployer.rms.task.RmsResultReporter;
import com.agv.navdeployer.rms.zenoh.ZenohChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RmsRobotSessionTest {

    @Test
    void closesFreshChannelWhenRegistrationFails() throws Exception {
        Fixture fixture = new Fixture();
        ZenohChannel fresh = mock(ZenohChannel.class);
        when(fixture.serviceGateway.register(any(), anyLong()))
                .thenReturn(new RmsServiceGateway.RegisterResult(false, null));

        RmsRobotSession session = fixture.session(ignored -> fresh);
        session.ensureSession();

        verify(fresh).open();
        verify(fresh).close();
        assertThat(ReflectionTestUtils.getField(session, "channel")).isNull();
        session.shutdown();
    }

    @Test
    void closesOldChannelAndCancelsOldReportTasksBeforeReconnect() throws Exception {
        Fixture fixture = new Fixture();
        fixture.props.getRobot().setRobotCode("RB-TEST-001");
        ZenohChannel first = mock(ZenohChannel.class);
        ZenohChannel second = mock(ZenohChannel.class);
        when(first.isOpen()).thenReturn(false);
        when(second.isOpen()).thenReturn(true);
        when(fixture.serviceGateway.register(any(), anyLong()))
                .thenReturn(new RmsServiceGateway.RegisterResult(true, "RB-TEST-001"));
        @SuppressWarnings("unchecked")
        Function<RmsProperties.Zenoh, ZenohChannel> factory = mock(Function.class);
        when(factory.apply(any())).thenReturn(first, second);

        RmsRobotSession session = fixture.session(factory);
        session.ensureSession();
        ScheduledFuture<?> firstHeartbeat = task(session, "heartbeatTask");
        ScheduledFuture<?> firstStatus = task(session, "statusTask");
        assertThat(scheduler(session).getQueue()).hasSize(2);

        session.ensureSession();

        verify(first).close();
        assertThat(firstHeartbeat.isCancelled()).isTrue();
        assertThat(firstStatus.isCancelled()).isTrue();
        assertThat(task(session, "heartbeatTask") == firstHeartbeat).isFalse();
        assertThat(task(session, "statusTask") == firstStatus).isFalse();
        assertThat(scheduler(session).getQueue()).hasSize(2);

        session.shutdown();
        assertThat(scheduler(session).getQueue()).isEmpty();
        verify(second).close();
        verify(factory, times(2)).apply(any());
    }

    private static ScheduledFuture<?> task(RmsRobotSession session, String field) {
        return (ScheduledFuture<?>) ReflectionTestUtils.getField(session, field);
    }

    private static ScheduledThreadPoolExecutor scheduler(RmsRobotSession session) {
        return (ScheduledThreadPoolExecutor) ReflectionTestUtils.getField(session, "scheduler");
    }

    private static final class Fixture {
        private final RmsProperties props = new RmsProperties();
        private final RobotStateView stateView = mock(RobotStateView.class);
        private final RmsReportGateway reportGateway = mock(RmsReportGateway.class);
        private final RmsServiceGateway serviceGateway = mock(RmsServiceGateway.class);
        private final RmsCommandGateway commandGateway = mock(RmsCommandGateway.class);
        private final RmsResultReporter resultReporter = mock(RmsResultReporter.class);

        private Fixture() {
            props.setEnabled(true);
            props.getZenoh().setContinueWithoutRegister(false);
            props.getZenoh().setQueryTimeoutMs(1000);
            props.getReport().setHeartbeatIntervalMs(60_000);
            props.getReport().setStatusIntervalMs(60_000);
        }

        private RmsRobotSession session(Function<RmsProperties.Zenoh, ZenohChannel> factory) {
            return new RmsRobotSession(props, stateView, reportGateway, serviceGateway,
                    commandGateway, resultReporter, new ObjectMapper(), factory);
        }
    }
}
