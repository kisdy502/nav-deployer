package com.agv.navdeployer.rms.infrastructure.gateway;

import com.agv.navdeployer.rms.infrastructure.inspection.RmsInspectionUploader;
import com.agv.navdeployer.rms.application.RmsMapService;
import com.agv.navdeployer.rms.application.RmsTelemetryService;
import com.agv.navdeployer.rms.infrastructure.log.RmsLogCollector;
import com.agv.navdeployer.rms.infrastructure.protocol.keys.BodySegment;
import com.agv.navdeployer.rms.infrastructure.protocol.keys.RmsCommandKeys;
import com.agv.navdeployer.rms.infrastructure.protocol.dto.command.BodyReply;
import com.agv.navdeployer.rms.infrastructure.protocol.dto.command.MapNameRequest;
import com.agv.navdeployer.rms.infrastructure.protocol.dto.command.RelocateRequest;
import com.agv.navdeployer.rms.infrastructure.protocol.dto.command.TaskCommandRequest;
import com.agv.navdeployer.rms.application.RmsTaskService;
import com.agv.navdeployer.rms.infrastructure.zenoh.RmsMessageLog;
import com.agv.navdeployer.rms.infrastructure.zenoh.ZenohChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zenoh.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class RmsCommandGateway {

    private static final Logger log = LoggerFactory.getLogger(RmsCommandGateway.class);

    private final RmsTaskService taskService;
    private final RmsMapService mapService;
    private final RmsTelemetryService telemetryService;
    private final RmsInspectionUploader inspectionUploader;
    private final RmsLogCollector logCollector;
    private final com.agv.navdeployer.rms.infrastructure.teach.RmsTeachCollector teachCollector;
    private final ObjectMapper mapper;
    private volatile RmsCommandKeys keys;
    private volatile ZenohChannel currentChannel;

    public RmsCommandGateway(RmsTaskService taskService,
                             RmsMapService mapService,
                             RmsTelemetryService telemetryService,
                             RmsInspectionUploader inspectionUploader,
                             RmsLogCollector logCollector,
                             com.agv.navdeployer.rms.infrastructure.teach.RmsTeachCollector teachCollector,
                             ObjectMapper mapper, RmsCommandKeys keys,
                             long periodicLogIntervalMs) {
        this.taskService = taskService;
        this.mapService = mapService;
        this.telemetryService = telemetryService;
        this.inspectionUploader = inspectionUploader;
        this.logCollector = logCollector;
        this.teachCollector = teachCollector;
        this.mapper = mapper;
        this.keys = keys;
        this.periodicLogIntervalMs = periodicLogIntervalMs;
    }

    public void updateKeys(RmsCommandKeys newKeys) {
        this.keys = newKeys;
    }

    public void attach(ZenohChannel channel) throws Exception {
        this.currentChannel = channel;
        int declared = 0;
        for (String segment : RmsCommandKeys.exactSegments()) {
            channel.declareQueryable(keys.bodyKey(segment), query -> dispatch(query, segment));
            declared++;
        }
        for (String wildcard : RmsCommandKeys.wildcardSegments()) {
            channel.declareQueryable(keys.bodyKey(wildcard), query -> dispatch(query, wildcard));
            declared++;
        }
        log.info("RMS command endpoints ready: {} queryables declared (exact + wildcard)", declared);
    }

    private static final java.util.Set<String> PERIODIC_SEGMENTS = java.util.Set.of(
            BodySegment.STATUS_QUERY.segment(),
            BodySegment.POSE_QUERY.segment(),
            BodySegment.CONFIG_GET.segment(),
            BodySegment.MODE_GET.segment(),
            BodySegment.MAPPING_STATUS.segment(),
            BodySegment.TASK_STATUS.segment(),
            BodySegment.TASK_TEMPLATE_QUERY.segment());

    private final long periodicLogIntervalMs;

    private static final java.util.Set<String> CLOSED_LOOP_SEGMENTS = java.util.Set.of(
            BodySegment.MAPPING_CHANGE.segment(),
            BodySegment.MAPPING_GET.segment(),
            BodySegment.MAPPING_DOWNLOAD.segment(),
            BodySegment.AGV_RELOCATE.segment());

    private final java.util.concurrent.ExecutorService closedLoopExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "rms-closed-loop");
                thread.setDaemon(true);
                return thread;
            });

    void dispatch(Query query, String declaredSegment) {
        String rawPayload = "{}";
        String actualSegment;
        try {
            rawPayload = ZenohChannel.readPayload(query);
            actualSegment = RmsCommandKeys.extractBodySegment(String.valueOf(query.getKeyExpr()));
            if (actualSegment.isBlank() || actualSegment.contains("*")) {
                actualSegment = declaredSegment;
            }
            if (BodySegment.MAPPING_DOWNLOAD.segment().equals(actualSegment)) {
                RmsMessageLog.inboundQuery(String.valueOf(query.getKeyExpr()), "[map URL redacted]");
            } else if (PERIODIC_SEGMENTS.contains(actualSegment)) {
                RmsMessageLog.inboundQueryThrottled(
                        String.valueOf(query.getKeyExpr()), rawPayload, periodicLogIntervalMs);
            } else {
                RmsMessageLog.inboundQuery(String.valueOf(query.getKeyExpr()), rawPayload);
            }
        } catch (Exception exception) {
            log.warn("RMS query read failed key={} msg={}", query.getKeyExpr(), exception.getMessage());
            replyAndClose(query, declaredSegment,
                    BodyReply.failure(500, "nav-deployer failed: " + exception.getMessage()));
            return;
        }
        if (CLOSED_LOOP_SEGMENTS.contains(actualSegment)) {
            final String payload = rawPayload;
            final String segment = actualSegment;
            closedLoopExecutor.submit(() -> {
                Object reply;
                try {
                    reply = handle(segment, payload);
                } catch (Exception exception) {
                    log.warn("RMS closed-loop query failed key={}", query.getKeyExpr(), exception);
                    reply = BodyReply.failure(500, "nav-deployer failed: " + exception.getMessage());
                }
                replyAndClose(query, segment, reply);
            });
            return;
        }
        Object reply;
        try {
            reply = handle(actualSegment, rawPayload);
        } catch (Exception exception) {
            log.warn("RMS query failed key={} payload={} msg={}",
                    query.getKeyExpr(), rawPayload, exception.getMessage(), exception);
            reply = BodyReply.failure(500, "nav-deployer failed: " + exception.getMessage());
        }
        replyAndClose(query, actualSegment, reply);
    }

    private void replyAndClose(Query query, String segment, Object reply) {
        try {
            String replyJson = mapper.writeValueAsString(reply);
            if (PERIODIC_SEGMENTS.contains(segment)) {
                log.info("RMS periodic query key={} segment={} reply_status={} reply_msg={} (节流)",
                        query.getKeyExpr(), segment, statusOf(reply), msgOf(reply));
                RmsMessageLog.outboundReplyThrottled(
                        String.valueOf(query.getKeyExpr()), replyJson, periodicLogIntervalMs);
            } else {
                log.info("RMS query key={} segment={} reply_status={} reply_msg={}",
                        query.getKeyExpr(), segment, statusOf(reply), msgOf(reply));
                RmsMessageLog.outboundReply(String.valueOf(query.getKeyExpr()), replyJson);
            }
            ZenohChannel.replyJson(query, replyJson);
        } catch (Exception serializeFailure) {
            log.error("RMS reply serialize failed, RMS will see query timeout: key={} segment={} reply={} msg={}",
                    query.getKeyExpr(), segment, reply.getClass().getSimpleName(), serializeFailure.getMessage(),
                    serializeFailure);
            ZenohChannel.replyError(query, "serialize reply failed");
        } finally {
            try {
                query.close();
            } catch (Exception ignored) {
            }
        }
    }

    private Object handle(String segment, String rawPayload) {
        String perTaskId = RmsCommandKeys.extractTaskIdFromSegment(segment);
        if (perTaskId != null) {
            String action = segment.split("/")[2];
            return control(action, perTaskId);
        }

        Optional<BodySegment> parsed = BodySegment.fromSegment(segment);
        if (parsed.isEmpty()) {
            return BodyReply.failure(404, "unknown segment: " + segment);
        }
        BodySegment bodySegment = parsed.get();
        if (!bodySegment.supported()) {
            return BodyReply.failure(501, "segment not supported by nav-deployer: " + segment);
        }
        BodyReply mapReply = dispatchMap(bodySegment, rawPayload);
        if (mapReply != null) {
            return mapReply;
        }
        TaskCommandRequest request = parseRequest(rawPayload);
        return switch (bodySegment) {
            case STATUS_QUERY -> taskService.statusQuery();
            case CONFIG -> taskService.configQuery();
            case TASK_TEMPLATE_QUERY -> taskService.templateQuery();
            case TASK_TEMPLATE_ADD -> taskService.addTemplate(request);
            case TASK_TEMPLATE_DELETE -> taskService.deleteTemplate(request.templateCode());
            case TASK_ADD -> taskService.addTask(request);
            case RESULT_FILES_UPLOAD_START -> inspectionUploader.handleUploadStart(
                    rawPayload, currentChannel, keys.bodyKeyBase());
            case LOG_COLLECT -> logCollector.handleCollect(rawPayload, currentChannel, keys.bodyKeyBase());
            case TEACH_CAPTURE -> teachCollector.handleCapture(rawPayload, currentChannel, keys.bodyKeyBase());
            case TASK_START -> taskService.startTask(request);
            case TASK_DELETE -> taskService.deleteTask(request.taskId());
            case TASK_PAUSE, TASK_PAUSE_TYPO -> taskService.pauseTask(request.taskId());
            case TASK_RESUME -> taskService.resumeTask(request.taskId());
            case TASK_STOP -> taskService.stopTask(request.taskId());
            case TASK_STATUS -> taskService.taskStatus(request.taskId());
            default -> BodyReply.failure(501, "segment not supported: " + segment);
        };
    }

    private static String statusOf(Object reply) {
        if (reply instanceof BodyReply bodyReply) {
            return String.valueOf(bodyReply.status());
        }
        if (reply instanceof com.fasterxml.jackson.databind.node.ObjectNode node) {
            return node.path("status").asText("?");
        }
        return "?";
    }

    private static String msgOf(Object reply) {
        if (reply instanceof BodyReply bodyReply) {
            return String.valueOf(bodyReply.msg());
        }
        if (reply instanceof com.fasterxml.jackson.databind.node.ObjectNode node) {
            return node.path("msg").asText("");
        }
        return "";
    }

    private BodyReply dispatchMap(BodySegment bodySegment, String rawPayload) {
        return switch (bodySegment) {
            case START_MAPPING -> mapService.startMapping(parseMapNameRequest(rawPayload));
            case STOP_MAPPING -> mapService.stopMapping(parseMapNameRequest(rawPayload));
            case MAPPING_STATUS -> mapService.mappingStatus();
            case MAPPING_LIST -> mapService.listMaps();
            case MAPPING_CHANGE -> mapService.changeMap(parseMapNameRequest(rawPayload));
            case MAPPING_GET_CURRENT -> mapService.getCurrentMap();
            case MAPPING_DELETE -> mapService.deleteMap(parseMapNameRequest(rawPayload));
            case MAPPING_DOWNLOAD -> mapService.downloadMap(rawPayload, mapper);
            case MAPPING_GET -> mapService.exportMap(rawPayload, mapper);
            case AGV_RELOCATE -> mapService.relocate(parseRelocateRequest(rawPayload));
            case MODE_SET -> mapService.modeSet();
            case MODE_GET -> mapService.modeGet();
            case POSE_QUERY -> telemetryService.poseQuery();
            case CONFIG_GET -> telemetryService.configGet();
            case CONFIG_EDIT -> telemetryService.configEdit(rawPayload);
            default -> null;
        };
    }

    private BodyReply control(String action, String taskId) {
        return switch (action) {
            case "pause", "puase" -> taskService.pauseTask(taskId);
            case "resume" -> taskService.resumeTask(taskId);
            case "stop" -> taskService.stopTask(taskId);
            default -> BodyReply.failure(404, "unknown control action: " + action);
        };
    }

    private TaskCommandRequest parseRequest(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return new TaskCommandRequest(null, null, null, null, null, null, null, null);
        }
        try {
            return mapper.readValue(rawPayload, TaskCommandRequest.class);
        } catch (Exception exception) {
            log.warn("failed to parse RMS request payload={} msg={}", rawPayload, exception.getMessage());
            return new TaskCommandRequest(null, null, null, null, null, null, null, null);
        }
    }

    private MapNameRequest parseMapNameRequest(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return new MapNameRequest(null);
        }
        try {
            return mapper.readValue(rawPayload, MapNameRequest.class);
        } catch (Exception exception) {
            log.warn("failed to parse RMS map-name payload={} msg={}", rawPayload, exception.getMessage());
            return new MapNameRequest(null);
        }
    }

    private RelocateRequest parseRelocateRequest(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return new RelocateRequest(null, null, null);
        }
        try {
            return mapper.readValue(rawPayload, RelocateRequest.class);
        } catch (Exception exception) {
            log.warn("failed to parse RMS relocate payload={} msg={}", rawPayload, exception.getMessage());
            return new RelocateRequest(null, null, null);
        }
    }
}
