package com.agv.navdeployer.rms.gateway;

import com.agv.navdeployer.rms.map.RmsInspectionUploader;
import com.agv.navdeployer.rms.map.RmsMapService;
import com.agv.navdeployer.rms.map.RmsTelemetryService;
import com.agv.navdeployer.rms.log.RmsLogCollector;
import com.agv.navdeployer.rms.protocol.keys.BodySegment;
import com.agv.navdeployer.rms.protocol.keys.RmsCommandKeys;
import com.agv.navdeployer.rms.protocol.dto.command.BodyReply;
import com.agv.navdeployer.rms.protocol.dto.command.MapNameRequest;
import com.agv.navdeployer.rms.protocol.dto.command.RelocateRequest;
import com.agv.navdeployer.rms.protocol.dto.command.TaskCommandRequest;
import com.agv.navdeployer.rms.task.RmsTaskService;
import com.agv.navdeployer.rms.zenoh.RmsMessageLog;
import com.agv.navdeployer.rms.zenoh.ZenohChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zenoh.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 【C 模式网关】RMS 查询 → 本机 queryable 应答。
 * attach 时声明全部指令端点；回调运行在 zenoh IO 线程，只做解析与同步回复，
 * 任务执行全部异步（RmsTaskService worker），保证 queryable 快速返回。
 */
public class RmsCommandGateway {

    private static final Logger log = LoggerFactory.getLogger(RmsCommandGateway.class);

    private final RmsTaskService taskService;
    private final RmsMapService mapService;
    private final RmsTelemetryService telemetryService;
    private final RmsInspectionUploader inspectionUploader;
    private final RmsLogCollector logCollector;
    private final com.agv.navdeployer.rms.teach.RmsTeachCollector teachCollector;
    private final com.agv.navdeployer.exchange.ScheduleMapAdapter scheduleAdapter;
    private final com.agv.navdeployer.service.NavPointService navPointService;
    private final com.agv.navdeployer.service.NavPathService navPathService;
    private final ObjectMapper mapper;
    private volatile RmsCommandKeys keys;
    private volatile ZenohChannel currentChannel;
    public RmsCommandGateway(RmsTaskService taskService,
                             RmsMapService mapService,
                             RmsTelemetryService telemetryService,
                             RmsInspectionUploader inspectionUploader,
                             RmsLogCollector logCollector,
                             com.agv.navdeployer.rms.teach.RmsTeachCollector teachCollector,
                             com.agv.navdeployer.exchange.ScheduleMapAdapter scheduleAdapter,
                             com.agv.navdeployer.service.NavPointService navPointService,
                             com.agv.navdeployer.service.NavPathService navPathService,
                             ObjectMapper mapper, RmsCommandKeys keys) {
        this.taskService = taskService;
        this.mapService = mapService;
        this.telemetryService = telemetryService;
        this.inspectionUploader = inspectionUploader;
        this.logCollector = logCollector;
        this.teachCollector = teachCollector;
        this.scheduleAdapter = scheduleAdapter;
        this.navPointService = navPointService;
        this.navPathService = navPathService;
        this.mapper = mapper;
        this.keys = keys;
    }

    /** 注册后更新 key（RMS 分配了新 robot_code 时调用） */
    public void updateKeys(RmsCommandKeys newKeys) {
        this.keys = newKeys;
    }

    /** 会话建立后声明全部指令端点（exact + 通配；重建时重调）。 */
    public void attach(ZenohChannel channel) throws Exception {
        this.currentChannel = channel;
        for (String segment : RmsCommandKeys.exactSegments()) {
            channel.declareQueryable(keys.bodyKey(segment), query -> dispatch(query, segment));
        }
        for (String wildcard : RmsCommandKeys.wildcardSegments()) {
            channel.declareQueryable(keys.bodyKey(wildcard), query -> dispatch(query, wildcard));
        }
    }

    /**
     * 周期轮询类段：RMS 定时重复查询（心跳/状态/配置等），不打高亮日志防刷屏，
     * 只保留一次性指令（task/add、task/start、建图、上传等）的完整报文。
     */
    private static final java.util.Set<String> PERIODIC_SEGMENTS = java.util.Set.of(
            BodySegment.STATUS_QUERY.segment(),
            BodySegment.POSE_QUERY.segment(),
            BodySegment.CONFIG_GET.segment(),
            BodySegment.MODE_GET.segment(),
            BodySegment.MAPPING_STATUS.segment(),
            BodySegment.TASK_STATUS.segment(),
            BodySegment.TASK_TEMPLATE_QUERY.segment());

    /**
     * 闭环指令段（切图 / 重定位）：业务需要等待机器人执行终态后才能给出成败结论，
     * 在独立线程执行并延迟回包，避免长时间阻塞 zenoh IO 线程（其他 queryable 仍在服务）。
     * 若 RMS 侧 query 超时先放弃，迟到的回包会被 zenoh 丢弃（reply 抛错仅记日志），不影响链路。
     */
    private static final java.util.Set<String> CLOSED_LOOP_SEGMENTS = java.util.Set.of(
            BodySegment.MAPPING_CHANGE.segment(),
            BodySegment.AGV_RELOCATE.segment());

    private final java.util.concurrent.ExecutorService closedLoopExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "rms-closed-loop");
                thread.setDaemon(true);
                return thread;
            });

    /** 统一入口（exact 与通配 queryable 都走这里）。reply 为 BodyReply 或 teach 等自定义信封 ObjectNode。 */
    void dispatch(Query query, String declaredSegment) {
        String rawPayload = "{}";
        String actualSegment;
        try {
            rawPayload = ZenohChannel.readPayload(query);
            actualSegment = RmsCommandKeys.extractBodySegment(String.valueOf(query.getKeyExpr()));
            if (actualSegment.isBlank() || actualSegment.contains("*")) {
                actualSegment = declaredSegment;
            }
            if (!PERIODIC_SEGMENTS.contains(actualSegment)) {
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
                    log.warn("RMS closed-loop query failed key={} payload={} msg={}",
                            query.getKeyExpr(), payload, exception.getMessage(), exception);
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

    /** 回包并关闭 query；非周期段打完整日志。 */
    private void replyAndClose(Query query, String segment, Object reply) {
        try {
            String replyJson = mapper.writeValueAsString(reply);
            if (!PERIODIC_SEGMENTS.contains(segment)) {
                log.info("RMS query key={} segment={} reply_status={} reply_msg={}",
                        query.getKeyExpr(), segment, statusOf(reply), msgOf(reply));
                RmsMessageLog.outboundReply(String.valueOf(query.getKeyExpr()), replyJson);
            }
            ZenohChannel.replyJson(query, replyJson);
        } catch (Exception serializeFailure) {
            ZenohChannel.replyError(query, "serialize reply failed");
        } finally {
            try {
                query.close();
            } catch (Exception ignored) {
                // 已关闭（含 RMS 提前超时放弃的迟到回包）
            }
        }
    }

    private Object handle(String segment, String rawPayload) {
        // task/{id}/pause|resume|stop 通配形态：从段里取 task_id
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
        // 地图/模式段走独立的 payload 结构，先于任务 DTO 解析
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

    /** 自定义信封（teach 等）的 status/msg 提取，统一日志格式。 */
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

    /** 地图/模式段分发；非地图段返回 null 走原有任务链路。 */
    private BodyReply dispatchMap(BodySegment bodySegment, String rawPayload) {
        return switch (bodySegment) {
            case START_MAPPING -> mapService.startMapping(parseMapNameRequest(rawPayload));
            case STOP_MAPPING -> mapService.stopMapping(parseMapNameRequest(rawPayload));
            case MAPPING_STATUS -> mapService.mappingStatus();
            case MAPPING_LIST -> mapService.listMaps();
            case MAPPING_CHANGE -> mapService.changeMap(parseMapNameRequest(rawPayload));
            case MAPPING_GET_CURRENT -> mapService.getCurrentMap();
            case MAPPING_DELETE -> mapService.deleteMap(parseMapNameRequest(rawPayload));
            case MAPPING_GET -> mapService.exportMap(rawPayload, mapper, scheduleAdapter, navPointService, navPathService);
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
