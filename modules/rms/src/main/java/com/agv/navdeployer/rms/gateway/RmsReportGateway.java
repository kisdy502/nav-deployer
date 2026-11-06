package com.agv.navdeployer.rms.gateway;

import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.protocol.dto.report.RobotStatusReport;
import com.agv.navdeployer.rms.protocol.dto.report.TaskResultEnvelope;
import com.agv.navdeployer.rms.protocol.keys.RmsReportKeys;
import com.agv.navdeployer.rms.state.RobotStateView;
import com.agv.navdeployer.rms.task.RmsResultReporter;
import com.agv.navdeployer.rms.zenoh.RmsMessageLog;
import com.agv.navdeployer.rms.zenoh.ZenohChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zenoh.pubsub.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public void updateKeys(RmsReportKeys newKeys) {
        this.keys = newKeys;
    }

    public void attach(ZenohChannel channel) {
        this.channel = channel;
    }

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

            String reportStatus = mapper.writeValueAsString(stateView.buildStatusEnvelope());
            if (reportProps.isVerboseLog()) {
                RmsMessageLog.outboundPublish(keys.bodyStatusReport(), reportStatus);
            }
            publisher.put(reportStatus);
        }
        if (reportProps.isPublishLegacyStatus()) {
            Publisher publisher = legacyStatusPublisher;
            if (publisher == null) {
                throw new IllegalStateException("legacy status publisher not initialized");
            }
            String reportOldStatus = mapper.writeValueAsString(stateView.snapshot());
            if (reportProps.isVerboseLog()) {
                RmsMessageLog.outboundPublish(keys.legacyStatusReport(), reportOldStatus);
            }
            publisher.put(reportOldStatus);
        }
    }

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

    @Override
    public void publish(TaskResultEnvelope envelope, String taskId) throws Exception {
        ZenohChannel current = channel;
        if (current == null || !current.isOpen()) {
            throw new IllegalStateException("rms channel not open");
        }
        String key = keys.taskResultReport(taskId);
        String result = mapper.writeValueAsString(envelope);
        current.putOnce(key, result);
        RmsMessageLog.outboundPublish(key, result);
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
