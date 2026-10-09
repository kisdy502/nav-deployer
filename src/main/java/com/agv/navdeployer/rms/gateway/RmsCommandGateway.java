package com.agv.navdeployer.rms.gateway;

import com.agv.navdeployer.rms.map.RmsInspectionUploader;
import com.agv.navdeployer.rms.map.RmsMapService;
import com.agv.navdeployer.rms.map.RmsTelemetryService;
import com.agv.navdeployer.rms.protocol.keys.BodySegment;
import com.agv.navdeployer.rms.protocol.keys.RmsCommandKeys;
import com.agv.navdeployer.rms.protocol.dto.command.BodyReply;
import com.agv.navdeployer.rms.protocol.dto.command.MapNameRequest;
import com.agv.navdeployer.rms.protocol.dto.command.RelocateRequest;
import com.agv.navdeployer.rms.protocol.dto.command.TaskCommandRequest;
import com.agv.navdeployer.rms.task.RmsTaskService;
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
    private final com.agv.navdeployer.exchange.ScheduleMapAdapter scheduleAdapter;
    private final com.agv.navdeployer.service.NavPointService navPointService;
    private final com.agv.navdeployer.service.NavPathService navPathService;
    private final ObjectMapper mapper;
    private volatile RmsCommandKeys keys;
    private volatile ZenohChannel currentChannel;

    public RmsCommandGateway(RmsTaskService taskService, RmsMapService mapService,
                             RmsTelemetryService telemetryService,
                             RmsInspectionUploader inspectionUploader,
                             com.agv.navdeployer.exchange.ScheduleMapAdapter scheduleAdapter,
                             com.agv.navdeployer.service.NavPointService navPointService,
                             com.agv.navdeployer.service.NavPathService navPathService,
                             ObjectMapper mapper, RmsCommandKeys keys) {
        this.taskService = taskService;
        this.mapService = mapService;
        this.telemetryService = telemetryService;
        this.inspectionUploader = inspectionUploader;
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

    /** 统一入口（exact 与通配 queryable 都走这里）。 */
    void dispatch(Query query, String declaredSegment) {
        String rawPayload = "{}";
        BodyReply reply;
        try {
            rawPayload = ZenohChannel.readPayload(query);
            String actualSegment = RmsCommandKeys.extractBodySegment(String.valueOf(query.getKeyExpr()));
            if (actualSegment.isBlank() || actualSegment.contains("*")) {
                actualSegment = declaredSegment;
            }
            reply = handle(actualSegment, rawPayload);
            log.info("RMS query key={} segment={} reply_status={} reply_msg={}",
                    query.getKeyExpr(), actualSegment, reply.status(), reply.msg());
        } catch (Exception exception) {
            log.warn("RMS query failed key={} payload={} msg={}",
                    query.getKeyExpr(), rawPayload, exception.getMessage(), exception);
            reply = BodyReply.failure(500, "nav-deployer failed: " + exception.getMessage());
        }
        try {
            ZenohChannel.replyJson(query, mapper.writeValueAsString(reply));
        } catch (Exception serializeFailure) {
            ZenohChannel.replyError(query, "serialize reply failed");
        } finally {
            try {
                query.close();
            } catch (Exception ignored) {
                // 已关闭
            }
        }
    }

    private BodyReply handle(String segment, String rawPayload) {
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
            case TASK_START -> taskService.startTask(request);
            case TASK_DELETE -> taskService.deleteTask(request.taskId());
            case TASK_PAUSE, TASK_PAUSE_TYPO -> taskService.pauseTask(request.taskId());
            case TASK_RESUME -> taskService.resumeTask(request.taskId());
            case TASK_STOP -> taskService.stopTask(request.taskId());
            case TASK_STATUS -> taskService.taskStatus(request.taskId());
            default -> BodyReply.failure(501, "segment not supported: " + segment);
        };
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
