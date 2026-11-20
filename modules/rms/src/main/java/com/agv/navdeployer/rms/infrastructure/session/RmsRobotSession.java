package com.agv.navdeployer.rms.infrastructure.session;

import com.agv.navdeployer.rms.infrastructure.config.RmsProperties;
import com.agv.navdeployer.rms.infrastructure.gateway.RmsCommandGateway;
import com.agv.navdeployer.rms.infrastructure.gateway.RmsReportGateway;
import com.agv.navdeployer.rms.infrastructure.gateway.RmsServiceGateway;
import com.agv.navdeployer.rms.infrastructure.protocol.dto.report.RobotStatusReport;
import com.agv.navdeployer.rms.infrastructure.protocol.dto.service.RegisterRequest;
import com.agv.navdeployer.rms.application.RobotStateView;
import com.agv.navdeployer.rms.domain.RmsResultReporter;
import com.agv.navdeployer.rms.infrastructure.zenoh.ZenohChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.OffsetDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public class RmsRobotSession {

    private static final Logger log = LoggerFactory.getLogger(RmsRobotSession.class);

    private final RmsProperties props;
    private final RobotStateView stateView;
    private final RmsReportGateway reportGateway;
    private final RmsServiceGateway serviceGateway;
    private final RmsCommandGateway commandGateway;
    private final RmsResultReporter resultReporter;
    private final ObjectMapper mapper;
    private final Function<RmsProperties.Zenoh, ZenohChannel> channelFactory;

    private final AtomicBoolean building = new AtomicBoolean(false);
    private final ScheduledThreadPoolExecutor scheduler = createReportScheduler();

    private volatile ZenohChannel channel;
    private volatile boolean registered;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> statusTask;
    private ScheduledFuture<?> positionTask;

    /**
     * 位置上报独立调度器：避免与心跳的同步 query（最长阻塞 queryTimeoutMs）互相抢占。
     */
    private final ScheduledThreadPoolExecutor positionScheduler = createPositionScheduler();

    /**
     * 上次已上报的位姿基准（x, y, yaw）与时间戳，用于位移/角度阈值判定。
     */
    private volatile double[] lastReportedPose;
    private volatile long lastPositionReportAt = 0L;
    private long lastPositionLogMs = 0L;

    public boolean isRegistered() {
        return registered;
    }

    public boolean isSessionOpen() {
        return channel != null;
    }

    private static ScheduledThreadPoolExecutor createReportScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "rms-report-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static ScheduledThreadPoolExecutor createPositionScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "rms-position-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    public RmsRobotSession(RmsProperties props,
                           RobotStateView stateView,
                           RmsReportGateway reportGateway,
                           RmsServiceGateway serviceGateway,
                           RmsCommandGateway commandGateway,
                           RmsResultReporter resultReporter,
                           ObjectMapper mapper) {
        this(props, stateView, reportGateway, serviceGateway, commandGateway,
                resultReporter, mapper, ZenohChannel::new);
    }

    RmsRobotSession(RmsProperties props,
                    RobotStateView stateView,
                    RmsReportGateway reportGateway,
                    RmsServiceGateway serviceGateway,
                    RmsCommandGateway commandGateway,
                    RmsResultReporter resultReporter,
                    ObjectMapper mapper,
                    Function<RmsProperties.Zenoh, ZenohChannel> channelFactory) {
        this.props = props;
        this.stateView = stateView;
        this.reportGateway = reportGateway;
        this.serviceGateway = serviceGateway;
        this.commandGateway = commandGateway;
        this.resultReporter = resultReporter;
        this.mapper = mapper;
        this.channelFactory = channelFactory;
    }

    public void start() {
        resultReporter.attach(reportGateway);
        ensureSession();
    }

    @Scheduled(fixedDelay = 5000)
    public void ensureSession() {
        if (!props.isEnabled()) {
            return;
        }
        ZenohChannel current = channel;
        if (current != null && current.isOpen()) {
            return;
        }
        if (!building.compareAndSet(false, true)) {
            return;
        }
        try {
            rebuild();
        } catch (Exception exception) {
            log.warn("rms session build failed, retry in 5s: {}", exception.getMessage());
            closeChannel();
        } finally {
            building.set(false);
        }
    }

    private void rebuild() throws Exception {
        closeChannel();
        RmsProperties.Robot robot = props.getRobot();

        ZenohChannel fresh = channelFactory.apply(props.getZenoh());
        boolean ownershipTransferred = false;
        try {
            fresh.open();
            reportGateway.attach(fresh);
            serviceGateway.attach(fresh);

            RmsServiceGateway.RegisterResult result =
                    serviceGateway.register(buildRegisterRequest(robot),
                            props.getZenoh().getQueryTimeoutMs());
            registered = result.success();
            if (!registered && !props.getZenoh().isContinueWithoutRegister()) {
                throw new IllegalStateException("RMS registration failed (robot_code=" + robot.getRobotCode() + ")");
            }

            // 关键：RMS 分配的 robot_code 可能与配置不同（首次注册 RMS 自动生成新 code）。
            String effectiveCode = (result.assignedRobotCode() != null && !result.assignedRobotCode().isBlank())
                    ? result.assignedRobotCode() : robot.getRobotCode();
            if (!effectiveCode.equals(robot.getRobotCode())) {
                log.info("RMS assigned robot_code: config={} -> effective={}",
                        robot.getRobotCode(), effectiveCode);
                stateView.setEffectiveRobotCode(effectiveCode);
                String prefix = props.getZenoh().getRobotKeyPrefix();
                reportGateway.updateKeys(new com.agv.navdeployer.rms.infrastructure.protocol.keys.RmsReportKeys(
                        prefix, robot.getRobotType(), effectiveCode));
                serviceGateway.updateKeys(new com.agv.navdeployer.rms.infrastructure.protocol.keys.RmsServiceKeys(
                        prefix, robot.getRobotType(), effectiveCode, props.getZenoh().getRegisterKey()));
                commandGateway.updateKeys(new com.agv.navdeployer.rms.infrastructure.protocol.keys.RmsCommandKeys(
                        prefix, robot.getRobotType(), effectiveCode));
            }

            reportGateway.declarePublishers();

            resultReporter.attach(reportGateway);

            commandGateway.attach(fresh);

            channel = fresh;
            ownershipTransferred = true;

            scheduleReports();
            log.info("RMS session ready: effective_robot_code={} robot_type={} registered={}",
                    effectiveCode, robot.getRobotType(), registered);
        } finally {
            if (!ownershipTransferred) {
                fresh.close();
            }
        }
    }

    private long lastHeartbeatLogMs = 0;
    private String lastHeartbeatSummary = "";

    private void heartbeatSafe() {
        try {
            String json = mapper.writeValueAsString(stateView.buildHeartbeatPayload());
            reportGateway.publishLegacyHeartbeat(json);
            boolean queryReplied = serviceGateway.heartbeatQuery(json, props.getZenoh().getQueryTimeoutMs());
            long now = System.currentTimeMillis();
            if (props.getReport().isVerboseLog()) {
                log.info("rms heartbeat beat: legacy_put=ok, body_query_replied={} (每 {}ms, verbose)",
                        queryReplied, props.getReport().getHeartbeatIntervalMs());
                return;
            }
            String summary = "legacy_put=ok, body_query_replied=" + queryReplied;
            boolean unchanged = summary.equals(lastHeartbeatSummary);
            long throttle = unchanged
                    ? Math.max(props.getReport().getPeriodicLogIntervalMs(), props.getReport().getUnchangedLogIntervalMs())
                    : 0;
            if (now - lastHeartbeatLogMs > throttle) {
                lastHeartbeatLogMs = now;
                lastHeartbeatSummary = summary;
                log.info("rms heartbeat: {} (每 {}ms{})", summary, props.getReport().getHeartbeatIntervalMs(),
                        unchanged ? ", 未变化降频" : "");
            }
        } catch (Exception exception) {
            log.warn("rms heartbeat failed: {}", exception.getMessage());
            markChannelBroken();
        }
    }

    private long lastStatusLogMs = 0;

    private void statusReportSafe() {
        try {
            reportGateway.publishStatus();
            long now = System.currentTimeMillis();
            long throttle = Math.max(props.getReport().getPeriodicLogIntervalMs(),
                    props.getReport().getUnchangedLogIntervalMs());
            if (now - lastStatusLogMs > throttle) {
                lastStatusLogMs = now;
                log.info("rms status report sent (body_put={} legacy_put={}, 每 {}ms)",
                        props.getReport().isPublishBodyStatus(), props.getReport().isPublishLegacyStatus(),
                        props.getReport().getStatusIntervalMs());
            }
        } catch (Exception exception) {
            log.warn("rms status report failed: {}", exception.getMessage());
            markChannelBroken();
        }
    }

    /**
     * 位置上报判定：距上次上报超过基线周期（默认 1 秒），或位移超过
     * {@code positionMoveThresholdM}（默认 5 厘米）/ 角度超过
     * {@code positionYawThresholdRad}（默认约 2.86 度）时立即上报。
     */
    private void positionReportSafe() {
        try {
            RmsProperties.Report report = props.getReport();
            var pose = stateView.currentPose();
            long now = System.currentTimeMillis();
            boolean intervalDue = now - lastPositionReportAt >= report.getPositionIntervalMs();
            boolean thresholdExceeded = false;
            if (pose != null) {
                double[] baseline = lastReportedPose;
                if (baseline == null) {
                    thresholdExceeded = true;
                } else {
                    double move = Math.hypot(pose.x() - baseline[0], pose.y() - baseline[1]);
                    double yawDelta = normalizeAngle(pose.yaw() - baseline[2]);
                    thresholdExceeded = move >= report.getPositionMoveThresholdM()
                            || Math.abs(yawDelta) >= report.getPositionYawThresholdRad();
                }
            }
            if (!intervalDue && !thresholdExceeded) {
                return;
            }
            reportGateway.publishPosition();
            lastPositionReportAt = now;
            if (pose != null) {
                lastReportedPose = new double[]{pose.x(), pose.y(), pose.yaw()};
            }
            long logThrottle = Math.max(props.getReport().getPeriodicLogIntervalMs(),
                    props.getReport().getUnchangedLogIntervalMs());
            if (now - lastPositionLogMs > logThrottle) {
                lastPositionLogMs = now;
                log.info("rms position report active (interval={}ms, moveThreshold={}m, yawThreshold={}rad)",
                        report.getPositionIntervalMs(), report.getPositionMoveThresholdM(),
                        report.getPositionYawThresholdRad());
            }
        } catch (Exception exception) {
            log.warn("rms position report failed: {}", exception.getMessage());
            markChannelBroken();
        }
    }

    private static double normalizeAngle(double angle) {
        return Math.atan2(Math.sin(angle), Math.cos(angle));
    }

    private RegisterRequest buildRegisterRequest(RmsProperties.Robot robot) {
        return new RegisterRequest(
                stateView.robotCode(), robot.getRobotSn(), robot.getRobotSn(), robot.getRobotName(),
                robot.getIp(), robot.getModel(), robot.getHardwareVersion(), robot.getFirmwareVersion(),
                robot.getClientVersion(),
                robot.getManufacturer(), null, null, robot.getRobotType(), null, null,
                robot.getProtocolVersion(),
                // api_version：RMS 页面「API 版本」取这个字段（protocol_version 它不读）
                robot.getProtocolVersion(),
                robot.getCapabilities().isEmpty() ? null : robot.getCapabilities(),
                OffsetDateTime.now());
    }

    private synchronized void scheduleReports() {
        cancelReports();
        RmsProperties.Report report = props.getReport();
        long heartbeatMs = Math.max(1000L, report.getHeartbeatIntervalMs());
        long statusMs = Math.max(1000L, report.getStatusIntervalMs());
        long positionTickMs = Math.max(50L, report.getPositionTickMs());
        ScheduledFuture<?> newHeartbeat = null;
        ScheduledFuture<?> newPosition = null;
        try {
            newHeartbeat = scheduler.scheduleAtFixedRate(
                    this::heartbeatSafe, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
            ScheduledFuture<?> newStatus = scheduler.scheduleAtFixedRate(
                    this::statusReportSafe, statusMs, statusMs, TimeUnit.MILLISECONDS);
            if (report.isPublishPosition()) {
                newPosition = positionScheduler.scheduleAtFixedRate(
                        this::positionReportSafe, positionTickMs, positionTickMs, TimeUnit.MILLISECONDS);
            }
            heartbeatTask = newHeartbeat;
            statusTask = newStatus;
            positionTask = newPosition;
        } catch (RuntimeException exception) {
            if (newHeartbeat != null) {
                newHeartbeat.cancel(false);
            }
            if (newPosition != null) {
                newPosition.cancel(false);
            }
            throw exception;
        }
    }

    private synchronized void cancelReports() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
        if (statusTask != null) {
            statusTask.cancel(false);
            statusTask = null;
        }
        if (positionTask != null) {
            positionTask.cancel(false);
            positionTask = null;
        }
    }

    private void markChannelBroken() {
        ZenohChannel current = channel;
        if (current != null) {
            current.markBroken();
        }
    }

    private void closeChannel() {
        cancelReports();
        ZenohChannel current = channel;
        channel = null;
        registered = false;
        if (current != null) {
            current.close();
        }
    }

    @PreDestroy
    public void shutdown() {
        resultReporter.detach();
        scheduler.shutdownNow();
        positionScheduler.shutdownNow();
        closeChannel();
    }
}
