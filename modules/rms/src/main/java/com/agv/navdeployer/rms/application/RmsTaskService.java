package com.agv.navdeployer.rms.application;

import com.agv.navdeployer.rms.domain.RmsTask;
import com.agv.navdeployer.rms.application.dto.RmsTaskDTO;
import com.agv.navdeployer.rms.domain.RmsTaskRegistry;
import com.agv.navdeployer.rms.domain.RmsResultReporter;
import com.agv.navdeployer.rms.domain.RmsTemplateStore;
import com.agv.navdeployer.rms.domain.RmsTaskStatus;
import com.agv.navdeployer.rms.infrastructure.config.RmsProperties;
import com.agv.navdeployer.rms.infrastructure.protocol.dto.command.BodyReply;
import com.agv.navdeployer.rms.infrastructure.protocol.dto.command.TaskCommandRequest;
import com.agv.navdeployer.task.application.ChargeService;
import com.agv.navdeployer.task.application.MapAlignmentGuard;
import com.agv.navdeployer.task.application.MoveTaskService;
import com.agv.navdeployer.task.application.RobotOperationalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * RMS 任务指令受理与生命周期编排。执行策略见 RmsTaskExecutor，机械臂质检序列见 RmsArmSequencer。
 */
@Component
public class RmsTaskService {

    private static final Logger log = LoggerFactory.getLogger(RmsTaskService.class);

    private final RmsTaskRegistry registry;
    private final RmsResultReporter resultReporter;
    private final RobotStateView stateView;
    private final MoveTaskService moveTaskService;
    private final RmsProperties props;
    private final RobotOperationalService operationalService;
    private final MapAlignmentGuard mapAlignmentGuard;
    private final ChargeService chargeService;
    private final RmsTemplateGate templateGate;
    private final RmsTaskExecutor executor;
    private final RmsTemplateStore templateStore;

    public RmsTaskService(RmsTaskRegistry registry,
                          RmsResultReporter resultReporter,
                          RobotStateView stateView,
                          MoveTaskService moveTaskService,
                          RmsProperties props,
                          RobotOperationalService operationalService,
                          MapAlignmentGuard mapAlignmentGuard,
                          ChargeService chargeService,
                          RmsTemplateGate templateGate,
                          RmsTaskExecutor executor,
                          RmsTemplateStore templateStore) {
        this.registry = registry;
        this.resultReporter = resultReporter;
        this.stateView = stateView;
        this.moveTaskService = moveTaskService;
        this.props = props;
        this.operationalService = operationalService;
        this.mapAlignmentGuard = mapAlignmentGuard;
        this.chargeService = chargeService;
        this.templateGate = templateGate;
        this.executor = executor;
        this.templateStore = templateStore;
    }

    private BodyReply mapGate() {
        if (mapAlignmentGuard == null) {
            return null;
        }
        try {
            mapAlignmentGuard.requireAligned();
            return null;
        } catch (IllegalStateException exception) {
            log.warn("rms task rejected by map alignment gate: {}", exception.getMessage());
            return BodyReply.failure(409, exception.getMessage());
        }
    }

    private BodyReply armGate(RmsTask task) {
        if (task.isArmGateExempt()) {
            return null;
        }
        if (executor.armMovementAllowed()) {
            return null;
        }
        String msg = "ARM_NOT_HOMED: arm_state=" + executor.armState()
                + "，机械臂未归位，拒绝移动任务（请先收臂归位后重试）";
        log.warn("rms task {} rejected by arm gate: {}", task.taskId(), msg);
        return BodyReply.failure(409, msg);
    }

    public BodyReply statusQuery() {
        return BodyReply.success("success", stateView.bodyStatusData());
    }

    public BodyReply configQuery() {
        RmsProperties.Robot robot = props.getRobot();
        return BodyReply.success("success", Map.of(
                "robot_code", robot.getRobotCode(),
                "robot_type", robot.getRobotType(),
                "capabilities", robot.getCapabilities()
        ));
    }

    public BodyReply templateQuery() {
        return BodyReply.success("success", allTemplates());
    }

    public BodyReply addTemplate(TaskCommandRequest request) {
        String code = request.templateCode() != null ? request.templateCode()
                : request.parameterText("template_code");
        if (code == null || code.isBlank()) {
            return BodyReply.failure(400, "template_code must not be blank");
        }
        String type = firstNonBlank(request.taskTemplateType(),
                request.parameterText("template_type"),
                request.parameterText("task_template_type"));
        String content = firstNonBlank(request.parameterText("template_content"),
                request.parameterText("content"),
                request.parameterText("template"),
                request.parameterText("xml"));
        RmsTemplateStore.Template saved = templateStore.save(code,
                firstNonBlank(request.templateId(), request.parameterText("template_id")),
                type,
                firstNonBlank(request.parameterText("template_description"), ""),
                content);
        log.info("RMS task_template/add: code={} type={} persisted", saved.code(), saved.type());
        return BodyReply.success("template add success", Map.of("template_code", code));
    }

    public BodyReply deleteTemplate(String templateCode) {
        if (templateCode == null || templateCode.isBlank()) {
            return BodyReply.failure(400, "template_code must not be blank");
        }
        boolean removed = templateStore.delete(templateCode);
        log.info("RMS task_template/delete: code={} removed={}", templateCode, removed);
        return BodyReply.success("template delete " + (removed ? "success" : "not found"),
                Map.of("template_code", templateCode));
    }

    private List<Map<String, String>> allTemplates() {
        List<Map<String, String>> all = new java.util.ArrayList<>(builtinTemplates());
        templateStore.list().forEach(template -> all.add(new java.util.LinkedHashMap<>(Map.of(
                "templates_id", template.templateId(),
                "template_code", template.code(),
                "template_type", template.type() == null ? "" : template.type(),
                "template_description", template.description() == null ? "" : template.description(),
                "template_add_time", template.addedAt() == null ? "" : template.addedAt()))));
        return all;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private List<Map<String, String>> builtinTemplates() {
        return List.of(
                template("rmf_navigate", "navigate"),
                template("quality_inspection", "action"),
                template("fetch_cable", "action"),
                template("charge", "action"),
                template("replace_battery", "action"),
                template("return_home", "action"),
                template("pause_task", "action"),
                template("resume_task", "action"));
    }

    private static Map<String, String> template(String code, String type) {
        return Map.of("templates_id", code, "template_code", code,
                "template_type", type, "template_description", code);
    }

    public BodyReply addTask(TaskCommandRequest request) {
        if (request.taskId() == null || request.taskId().isBlank()) {
            return BodyReply.failure(400, "task_id must not be blank");
        }
        RmsTask existing = registry.find(request.taskId()).orElse(null);
        if (existing != null && !existing.status().terminal()) {
            return BodyReply.failure(409, "task_id in flight (status="
                    + existing.status().wireName() + "), stop first");
        }
        RmsTask task = new RmsTask(request);
        registry.put(task);
        logRmsCommand("task/add", task);
        return BodyReply.success("task add success", task.toInfoReport());
    }

    public BodyReply startTask(TaskCommandRequest request) {
        if (request.taskId() == null || request.taskId().isBlank()) {
            return BodyReply.failure(400, "task_id must not be blank");
        }
        if (operationalService.isDecommissioned()) {
            log.warn("rms task {} rejected: robot decommissioned ({})", request.taskId(), operationalService.getReason());
            return BodyReply.failure(503,
                    "robot is decommissioned (" + operationalService.getReason() + "), not accepting tasks");
        }
        RmsTask task = registry.find(request.taskId()).orElse(null);
        if (task == null || task.status().terminal()) {
            if (task != null) {
                log.info("rms task {} reused for next stage: old={} new template={} type={}",
                        task.taskId(), task.status().wireName(),
                        request.taskTemplateType(), request.taskType());
            }
            task = new RmsTask(request);
            registry.put(task);
        }
        if (registry.busyExcept(task.taskId())) {
            return BodyReply.failure(409, "robot busy: 已有在途任务 "
                    + registry.activeTask().taskId() + "，请先 stop/pause");
        }
        if (task.status() == RmsTaskStatus.RUNNING) {
            return BodyReply.failure(409, "task already running");
        }
        BodyReply mapRejected = mapGate();
        if (mapRejected != null) {
            return mapRejected;
        }
        BodyReply armRejected = armGate(task);
        if (armRejected != null) {
            return armRejected;
        }
        if (templateGate.templateMissing(task)) {
            log.warn("rms task {} failed: template not downloaded (type={}, code={})",
                    task.taskId(), task.taskTemplateType(), task.templateCode());
            executor.fail(task, RmsTaskStatus.FAILED, templateGate.templateMissingMessage(task));
            return BodyReply.failure(2, templateGate.templateMissingMessage(task));
        }
        logRmsCommand("task/start", task);
        registry.transition(task.taskId(), RmsTaskStatus.RUNNING);
        final String startedTaskId = task.taskId();
        executor.execute(startedTaskId);
        return BodyReply.success("started", task.toInfoReport());
    }

    public BodyReply pauseTask(String taskId) {
        RmsTask task = registry.find(taskId).orElse(null);
        if (task == null) {
            return BodyReply.failure(404, "task not found: " + taskId);
        }
        if (task.status() != RmsTaskStatus.RUNNING) {
            return BodyReply.failure(409, "task not running (status=" + task.status().wireName() + ")");
        }
        logRmsCommand("task/pause", task);
        registry.transition(taskId, RmsTaskStatus.PAUSED);
        cancelInternalMove(task);
        return BodyReply.success("paused", task.toInfoReport());
    }

    public BodyReply resumeTask(String taskId) {
        RmsTask task = registry.find(taskId).orElse(null);
        if (task == null) {
            return BodyReply.failure(404, "task not found: " + taskId);
        }
        if (task.status() != RmsTaskStatus.PAUSED) {
            return BodyReply.failure(409, "task not paused (status=" + task.status().wireName() + ")");
        }
        BodyReply mapRejected = mapGate();
        if (mapRejected != null) {
            return mapRejected;
        }
        BodyReply armRejected = armGate(task);
        if (armRejected != null) {
            return armRejected;
        }
        logRmsCommand("task/resume", task);
        registry.transition(taskId, RmsTaskStatus.RUNNING);
        executor.execute(task.taskId());
        return BodyReply.success("resumed", task.toInfoReport());
    }

    public BodyReply stopTask(String taskId) {
        RmsTask task = registry.find(taskId).orElse(null);
        if (task == null) {
            return BodyReply.failure(404, "task not found: " + taskId);
        }
        if (task.status().terminal()) {
            return BodyReply.failure(409, "task already " + task.status().wireName());
        }
        logRmsCommand("task/stop", task);
        registry.transition(taskId, RmsTaskStatus.CANCELED);
        task.setMessage("task cancelled");
        registry.save(task);
        cancelInternalMove(task);
        resultReporter.report(task);
        return BodyReply.success("stopped", task.toInfoReport());
    }

    public RmsTaskDTO activeTaskView() {
        RmsTask active = registry.activeTask();
        return active == null ? null : RmsTaskDTO.from(active);
    }

    public List<RmsTaskDTO> listTasks(int limit) {
        return registry.history(limit).stream()
                .map(record -> new RmsTaskDTO(
                        record.getTaskId(), record.getActionId(), record.getTaskType(),
                        record.getTaskTemplateType(), record.getTemplateCode(), record.getTemplateId(),
                        parseParameters(record.getParameters()), record.getStatus(),
                        RmsTaskStatus.fromWire(record.getStatus()).actionStatusCode(),
                        record.getMessage(), record.getDestX(), record.getDestY(), record.getDestYaw(),
                        record.getTargetLabel(),
                        record.getInternalMoveTaskId(), record.getInternalChargeTaskId(),
                        record.getCreatedAt(), record.getStartedAt(), record.getFinishedAt()))
                .toList();
    }

    public synchronized List<RmsTaskDTO> resetInFlight(String reason) {
        List<RmsTask> inFlight = registry.all().stream()
                .filter(task -> !task.status().terminal())
                .toList();
        for (RmsTask task : inFlight) {
            log.warn("rms task {} manual reset from ops page: status={} reason={}",
                    task.taskId(), task.status(), reason);
            cancelInternalMove(task);
            task.setMessage(reason);
            registry.transition(task.taskId(), RmsTaskStatus.CANCELED);
            try {
                resultReporter.report(task);
            } catch (Exception exception) {
                log.warn("rms task {} reset result_report failed: {}", task.taskId(), exception.getMessage());
            }
        }
        return inFlight.stream().map(RmsTaskDTO::from).toList();
    }

    private Map<String, Object> parseParameters(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    public BodyReply taskStatus(String taskId) {
        RmsTask task = registry.find(taskId).orElse(null);
        if (task == null) {
            return BodyReply.failure(404, "task not found: " + taskId);
        }
        return BodyReply.success("success", task.toInfoReport());
    }

    public BodyReply deleteTask(String taskId) {
        RmsTask task = registry.find(taskId).orElse(null);
        if (task == null) {
            return BodyReply.success("task deleted", Map.of("task_id", taskId == null ? "" : taskId));
        }
        if (task.status() == RmsTaskStatus.RUNNING || task.status() == RmsTaskStatus.PAUSED) {
            return BodyReply.failure(409, "task in flight, stop first");
        }
        registry.remove(taskId);
        return BodyReply.success("task deleted", task.toInfoReport());
    }

    private void cancelInternalMove(RmsTask task) {
        Long moveTaskId = task.internalMoveTaskId();
        if (moveTaskId != null) {
            try {
                moveTaskService.cancel(moveTaskId);
            } catch (Exception exception) {
                log.debug("cancel internal move task {} for rms task {}: {}",
                        moveTaskId, task.taskId(), exception.getMessage());
            }
        }
        Long chargeTaskId = task.internalChargeTaskId();
        if (chargeTaskId != null) {
            try {
                chargeService.cancel("rms task pause/stop: " + task.taskId());
            } catch (Exception exception) {
                log.debug("cancel internal charge task {} for rms task {}: {}",
                        chargeTaskId, task.taskId(), exception.getMessage());
            }
        }
    }

    private void logRmsCommand(String command, RmsTask task) {
        log.info("RMS 指令 {} task_id={} action_id={} template={} type={} parameters={}",
                command, task.taskId(), task.actionId(),
                task.taskTemplateType(), task.taskType(), task.parameters());
    }
}
