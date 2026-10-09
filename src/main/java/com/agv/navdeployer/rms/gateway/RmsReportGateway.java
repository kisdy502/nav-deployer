package com.agv.navdeployer.rms.gateway;

import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.protocol.dto.report.RobotStatusReport;
import com.agv.navdeployer.rms.protocol.dto.report.TaskResultEnvelope;
import com.agv.navdeployer.rms.protocol.keys.RmsReportKeys;
import com.agv.navdeployer.rms.state.RobotStateView;
import com.agv.navdeployer.rms.task.RmsResultReporter;
import com.agv.navdeployer.rms.zenoh.ZenohChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zenoh.pubsub.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 【A 模式网关】robot 单向发布：状态上报、legacy 心跳 put、任务 result_report。
 * 这里所有操作都是 putOnce——发出去不等回复；报文一律来自 {@link RobotStateView}。
 */
public class RmsReportGateway implements RmsResultReporter.Sink {

    private static final Logger log = LoggerFactory.getLogger(RmsReportGateway.class);

    private volatile RmsReportKeys keys;
    private final RobotStateView stateView;
    private final ObjectMapper mapper;
    private final RmsProperties.Report reportProps;
    private volatile ZenohChannel channel;

    private volatile Publisher bodyStatusPublisher;
    private volatile Publisher legacyStatusPublisher;
    private volatile Publisher legacyHeartbeatPublisher;

    public RmsReportGateway(RmsReportKeys keys, RobotStateView stateView,
                            ObjectMapper mapper, RmsProperties.Report reportProps) {
        this.keys = keys;
        this.stateView = stateView;
        this.mapper = mapper;
        this.reportProps = reportProps;
    }

    /**
     * 注册后更新 key（RMS 分配了新 robot_code 时调用）
     */
    public void updateKeys(RmsReportKeys newKeys) {
        this.keys = newKeys;
    }

    /**
     * 会话建立后注入通道（重建时重调）。
     */
    public void attach(ZenohChannel channel) {
        this.channel = channel;
    }

    /**
     * 状态上报（body 通道发信封格式，legacy 通道发平铺格式）。
     */
    public void publishStatus() throws Exception {
        ZenohChannel current = channel;
        if (current == null || !current.isOpen()) {
            return;
        }
        if (reportProps.isPublishBodyStatus()) {
            Publisher publisher = bodyStatusPublisher;
            if (publisher == null) {
                throw new IllegalStateException("body status publisher not initialized");
            }

            // body 通道：{data: {...}, serial_num, timestamp}（对齐 RMS 真机格式）
            String reportStatus = mapper.writeValueAsString(stateView.buildStatusEnvelope());
            log.info("上报状态:{}", keys.bodyStatusReport());
            publisher.put(reportStatus);
        }
        if (reportProps.isPublishLegacyStatus()) {
            Publisher publisher = legacyStatusPublisher;
            if (publisher == null) {
                throw new IllegalStateException("legacy status publisher not initialized");
            }
            // legacy 通道：平铺格式（兼容旧版 RMS）
            String reportOldStatus = mapper.writeValueAsString(stateView.snapshot());
            log.info("上报旧格式状态:{}", reportOldStatus);
            publisher.put(reportOldStatus);
        }
    }

    /**
     * legacy 心跳 put（与 B 模式的心跳 query 同节拍发出的伴生通道）。
     */
    public void publishLegacyHeartbeat(String heartbeatJson) throws Exception {
        ZenohChannel current = channel;
        if (current == null || !current.isOpen()) {
            return;
        }
        Publisher publisher = legacyHeartbeatPublisher;

        if (publisher == null) {
            throw new IllegalStateException("heartbeat publisher not initialized");
        }

        publisher.put(heartbeatJson);

    }

    /**
     * 任务终态上报 result_report（RmsResultReporter 的下沉出口）。
     */
    @Override
    public void publish(TaskResultEnvelope envelope, String taskId) throws Exception {
        ZenohChannel current = channel;
        if (current == null || !current.isOpen()) {
            throw new IllegalStateException("rms channel not open");
        }
        current.putOnce(keys.taskResultReport(taskId), mapper.writeValueAsString(envelope));
        log.info("RMS result_report published: task_id={} action_status={}",
                taskId, envelope.data().actionStatus());
    }

    public void declarePublishers() throws Exception {
        ZenohChannel current = channel;

        if (current == null || !current.isOpen()) {
            throw new IllegalStateException("Zenoh channel not open");
        }

        bodyStatusPublisher = null;
        legacyStatusPublisher = null;
        legacyHeartbeatPublisher = null;

        if (reportProps.isPublishBodyStatus()) {
            bodyStatusPublisher =
                    current.declarePublisher(keys.bodyStatusReport());
        }

        if (reportProps.isPublishLegacyStatus()) {
            legacyStatusPublisher =
                    current.declarePublisher(keys.legacyStatusReport());
        }

        legacyHeartbeatPublisher =
                current.declarePublisher(keys.legacyHeartbeat());
    }
}
