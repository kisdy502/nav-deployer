package com.agv.navdeployer.rms.session;

import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.gateway.RmsCommandGateway;
import com.agv.navdeployer.rms.gateway.RmsReportGateway;
import com.agv.navdeployer.rms.gateway.RmsServiceGateway;
import com.agv.navdeployer.rms.protocol.dto.report.RobotStatusReport;
import com.agv.navdeployer.rms.protocol.dto.service.RegisterRequest;
import com.agv.navdeployer.rms.state.RobotStateView;
import com.agv.navdeployer.rms.task.RmsResultReporter;
import com.agv.navdeployer.rms.zenoh.ZenohChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.OffsetDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RMS 会话编排（只管生命周期，不管任何一方向的通信细节）：
 * 通道重建 → 注册（B 网关）→ 声明指令端点（C 网关）→ 心跳/状态调度（A+B 网关）。
 * 三个网关各管一个方向：A=robot 发布（RmsReportGateway）、B=robot 查询（RmsServiceGateway）、
 * C=RMS 查询应答（RmsCommandGateway）。
 */
public class RmsRobotSession {

    private static final Logger log = LoggerFactory.getLogger(RmsRobotSession.class);

    private final RmsProperties props;
    private final RobotStateView stateView;
    private final RmsReportGateway reportGateway;
    private final RmsServiceGateway serviceGateway;
    private final RmsCommandGateway commandGateway;
    private final RmsResultReporter resultReporter;
    private final ObjectMapper mapper;

    private final AtomicBoolean building = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1, runnable -> {
                Thread thread = new Thread(runnable, "rms-report-scheduler");
                thread.setDaemon(true);
                return thread;
            });

    private volatile ZenohChannel channel;
    private volatile boolean registered;

    public RmsRobotSession(RmsProperties props,
                           RobotStateView stateView,
                           RmsReportGateway reportGateway,
                           RmsServiceGateway serviceGateway,
                           RmsCommandGateway commandGateway,
                           RmsResultReporter resultReporter,
                           ObjectMapper mapper) {
        this.props = props;
        this.stateView = stateView;
        this.reportGateway = reportGateway;
        this.serviceGateway = serviceGateway;
        this.commandGateway = commandGateway;
        this.resultReporter = resultReporter;
        this.mapper = mapper;
    }

    public void start() {
        resultReporter.attach(reportGateway);
        ensureSession();
    }

    /** 会话维护：断线（或未建）时重建；连接抖动由 building 标志去抖。 */
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

        ZenohChannel fresh = new ZenohChannel(props.getZenoh());
        fresh.open();
        channel = fresh;
        reportGateway.attach(fresh);
        serviceGateway.attach(fresh);

        // B：注册（失败策略按 continue_without_register）
        RmsServiceGateway.RegisterResult result =
                serviceGateway.register(buildRegisterRequest(robot),
                        props.getZenoh().getQueryTimeoutMs());
        registered = result.success();
        if (!registered && !props.getZenoh().isContinueWithoutRegister()) {
            throw new IllegalStateException("RMS registration failed (robot_code=" + robot.getRobotCode() + ")");
        }

        // 关键：RMS 分配的 robot_code 可能与配置不同（首次注册 RMS 自动生成新 code）。
        // 后续心跳/状态/任务必须用分配值，否则 RMS 按 code 查不到机器人 → 永远离线。
        // 与 mock 的 applyRegistrationReply 同语义。
        String effectiveCode = (result.assignedRobotCode() != null && !result.assignedRobotCode().isBlank())
                ? result.assignedRobotCode() : robot.getRobotCode();
        if (!effectiveCode.equals(robot.getRobotCode())) {
            log.info("RMS assigned robot_code: config={} -> effective={}",
                    robot.getRobotCode(), effectiveCode);
            stateView.setEffectiveRobotCode(effectiveCode);
            // 用 effective code 重建三个 key 集
            String prefix = props.getZenoh().getRobotKeyPrefix();
            reportGateway.updateKeys(new com.agv.navdeployer.rms.protocol.keys.RmsReportKeys(
                    prefix, robot.getRobotType(), effectiveCode));
            serviceGateway.updateKeys(new com.agv.navdeployer.rms.protocol.keys.RmsServiceKeys(
                    prefix, robot.getRobotType(), effectiveCode, props.getZenoh().getRegisterKey()));
            commandGateway.updateKeys(new com.agv.navdeployer.rms.protocol.keys.RmsCommandKeys(
                    prefix, robot.getRobotType(), effectiveCode));
        }

        // C：声明全部指令端点（queryable key 已用 effective code）
        commandGateway.attach(fresh);

        // A+B：心跳/状态定时调度（payload 已用 effective code）
        scheduleReports();
        log.info("RMS session ready: effective_robot_code={} robot_type={} registered={}",
                effectiveCode, robot.getRobotType(), registered);
    }

    /** 心跳 = A 网关 legacy put + B 网关 body query，同一份报文双通道。 */
    private long lastHeartbeatLogMs = 0;

    private void heartbeatSafe() {
        try {
            // 心跳 payload = 简单身份信息（对齐 RMS 真机格式）
            String json = mapper.writeValueAsString(stateView.buildHeartbeatPayload());
            reportGateway.publishLegacyHeartbeat(json);
            boolean queryReplied = serviceGateway.heartbeatQuery(json, props.getZenoh().getQueryTimeoutMs());
            long now = System.currentTimeMillis();
            if (now - lastHeartbeatLogMs > 30_000) {
                lastHeartbeatLogMs = now;
                log.info("rms heartbeat: legacy_put=ok, body_query_replied={} (每 {}ms)",
                        queryReplied, props.getReport().getHeartbeatIntervalMs());
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
            if (now - lastStatusLogMs > 30_000) {
                lastStatusLogMs = now;
                log.info("rms status report sent (body_put + legacy_put)");
            }
        } catch (Exception exception) {
            log.warn("rms status report failed: {}", exception.getMessage());
            markChannelBroken();
        }
    }

    private RegisterRequest buildRegisterRequest(RmsProperties.Robot robot) {
        // robot_code 取 effective（首次注册 RMS 分配后由 stateView 持有），
        // 空则回退配置值（首次注册）。这样 RMS 重注册时能按 code 匹配更新而非新建。
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

    private void scheduleReports() {
        RmsProperties.Report report = props.getReport();
        long heartbeatMs = Math.max(1000L, report.getHeartbeatIntervalMs());
        long statusMs = Math.max(1000L, report.getStatusIntervalMs());
        scheduler.scheduleAtFixedRate(this::heartbeatSafe, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::statusReportSafe, statusMs, statusMs, TimeUnit.MILLISECONDS);
    }

    private void markChannelBroken() {
        ZenohChannel current = channel;
        if (current != null) {
            current.markBroken();
        }
    }

    private void closeChannel() {
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
        closeChannel();
    }
}
