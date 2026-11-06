package com.agv.navdeployer.rms.task;

import com.agv.navdeployer.dto.MoveTaskCreateDTO;
import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.protocol.dto.command.BodyReply;
import com.agv.navdeployer.rms.protocol.dto.command.TaskCommandRequest;
import com.agv.navdeployer.rms.state.RobotStateView;
import com.agv.navdeployer.service.MoveTaskService;
import com.agv.navdeployer.sim.RosCommandDispatcher;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import com.agv.navdeployer.vo.MoveTaskVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class RmsTaskService {

    private static final Logger log = LoggerFactory.getLogger(RmsTaskService.class);
    private static final long MOVE_POLL_INTERVAL_MS = 500L;

    private final RmsTaskRegistry registry;
    private final RmsResultReporter resultReporter;
    private final RobotStateView stateView;
    private final MoveTaskService moveTaskService;
    private final RmsProperties props;
    private final com.agv.navdeployer.sim.ArmStateMonitor armStateMonitor;
    private final com.agv.navdeployer.sim.RosCommandDispatcher armDispatcher;
    private final com.agv.navdeployer.sim.SimAgvTelemetry armTelemetry;
    private final com.agv.navdeployer.sim.SimAgvProperties simProps;
    private final com.agv.navdeployer.service.SmartNavigationService smartNavigation;
    private final com.agv.navdeployer.service.ChargeService chargeService;
    private final com.agv.navdeployer.service.RobotOperationalService operationalService;
    private final com.agv.navdeployer.service.MapAlignmentGuard mapAlignmentGuard;

    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "rms-task-worker");
                thread.setDaemon(true);
                return thread;
            });

    private final RmsTemplateStore templateStore;

    public RmsTaskService(RmsTaskRegistry registry,
                          RmsResultReporter resultReporter,
                          RobotStateView stateView,
                          MoveTaskService moveTaskService,
                          RmsProperties props,
                          com.agv.navdeployer.sim.ArmStateMonitor armStateMonitor,
                          com.agv.navdeployer.sim.RosCommandDispatcher armDispatcher,
                          com.agv.navdeployer.sim.SimAgvTelemetry armTelemetry,
                          com.agv.navdeployer.sim.SimAgvProperties simProps,
                           com.agv.navdeployer.service.SmartNavigationService smartNavigation,
                           com.agv.navdeployer.service.ChargeService chargeService,
                           com.agv.navdeployer.service.RobotOperationalService operationalService,
                           com.agv.navdeployer.service.MapAlignmentGuard mapAlignmentGuard,
                           RmsTemplateStore templateStore) {
        this.registry = registry;
        this.resultReporter = resultReporter;
        this.stateView = stateView;
        this.moveTaskService = moveTaskService;
        this.props = props;
        this.armStateMonitor = armStateMonitor;
        this.armDispatcher = armDispatcher;
        this.armTelemetry = armTelemetry;
        this.simProps = simProps;
        this.smartNavigation = smartNavigation;
        this.chargeService = chargeService;
        this.operationalService = operationalService;
        this.mapAlignmentGuard = mapAlignmentGuard;
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

    private boolean templateMissing(RmsTask task) {
        if (!task.isAction()) {
            return false;
        }
        if (!(task.isTemplate("charge") || task.isTemplate("replace_battery")
                || task.isTemplate("quality_inspection"))) {
            return false;
        }
        String type = task.taskTemplateType() == null ? "charge" : task.taskTemplateType();
        String code = task.templateCode() == null || task.templateCode().isBlank()
                ? type : task.templateCode();
        return templateStore.find(code).isEmpty();
    }

    private String templateMissingMessage(RmsTask task) {
        String type = task.taskTemplateType() == null ? "charge" : task.taskTemplateType();
        String code = task.templateCode() == null || task.templateCode().isBlank()
                ? type : task.templateCode();
        String templateId = task.templateId();
        return "任务模板不存在：类型 " + type + " 的任务模板 " + code
                + (templateId == null || templateId.isBlank() ? "" : "（template_id=" + templateId + "）")
                + " 未下发，请先通过 task_template/add 下发模板";
    }

    private BodyReply armGate(RmsTask task) {
        if (armStateMonitor == null || task.isArmGateExempt()) {
            return null;
        }
        if (armStateMonitor.movementAllowed()) {
            return null;
        }
        String msg = "ARM_NOT_HOMED: arm_state=" + armStateMonitor.state()
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
        if (templateMissing(task)) {
            log.warn("rms task {} failed: template not downloaded (type={}, code={})",
                    task.taskId(), task.taskTemplateType(), task.templateCode());
            fail(task, RmsTaskStatus.FAILED, templateMissingMessage(task));
            return BodyReply.failure(2, templateMissingMessage(task));
        }
        logRmsCommand("task/start", task);
        registry.transition(task.taskId(), RmsTaskStatus.RUNNING);
        final String startedTaskId = task.taskId();
        worker.execute(() -> execute(startedTaskId));
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
        worker.execute(() -> execute(task.taskId()));
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

    public RmsTaskVO activeTaskView() {
        RmsTask active = registry.activeTask();
        return active == null ? null : RmsTaskVO.from(active);
    }

    public List<RmsTaskVO> listTasks(int limit) {
        return registry.history(limit).stream()
                .map(record -> new RmsTaskVO(
                        record.getTaskId(), record.getActionId(), record.getTaskType(),
                        record.getTaskTemplateType(), record.getTemplateCode(), record.getTemplateId(),
                        parseParameters(record.getParameters()), record.getStatus(),
                        RmsTaskStatus.fromWire(record.getStatus()).actionStatusCode(),
                        record.getMessage(), record.getDestX(), record.getDestY(), record.getDestYaw(),
                        record.getInternalMoveTaskId(), record.getInternalChargeTaskId(),
                        record.getCreatedAt(), record.getStartedAt(), record.getFinishedAt()))
                .toList();
    }

    public synchronized List<RmsTaskVO> resetInFlight(String reason) {
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
        return inFlight.stream().map(RmsTaskVO::from).toList();
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

    private void execute(String taskId) {
        RmsTask task = registry.find(taskId).orElse(null);
        if (task == null || task.status() != RmsTaskStatus.RUNNING) {
            return;
        }
        if (task.isInspectionAction()) {
            simulateInspection(task);
            return;
        }
        if (task.isArmGateExempt()) {
            simulateAction(task);
            return;
        }
        if (task.isNavigation()) {
            dispatchMove(task);
            return;
        }
        if (task.isTemplate("charge") || task.isTemplate("replace_battery")) {
            if (templateMissing(task)) {
                log.warn("rms task {} failed: template not downloaded (type={}, code={})",
                        task.taskId(), task.taskTemplateType(), task.templateCode());
                fail(task, RmsTaskStatus.FAILED, templateMissingMessage(task));
                return;
            }
            executeCharge(task);
            return;
        }
        dispatchMove(task);
    }

    private void dispatchMove(RmsTask task) {
        String taskId = task.taskId();
        TaskCommandRequest.Destination destination = resolveDestination(task);
        if (destination == null) {
            fail(task, RmsTaskStatus.AGV_NODE_FAILED, "no destination for task (template=" + task.taskTemplateType()
                    + ", home pose 未配置且 parameters.destination 缺失)");
            return;
        }
        task.setDestination(destination);
        try {
            // source=rms:{task_id}：任务页据此把同一 RMS 任务的多阶段执行（导航→充电）关联展示
            MoveTaskVO moveTask = smartNavigation.createOptimizedMoveTask(taskId,
                    destination.x(), destination.y(), destination.yaw(), "rms:" + taskId);
            task.setInternalMoveTaskId(moveTask.getId());
            registry.save(task);
            log.info("rms task {} dispatched internal move task {} (type={}) -> ({}, {}, {}rad)",
                    taskId, moveTask.getId(), moveTask.getTaskType(),
                    destination.x(), destination.y(), destination.yaw());
            scheduleMovePoll(taskId, moveTask.getId());
        } catch (Exception exception) {
            fail(task, RmsTaskStatus.AGV_NODE_FAILED, exception.getMessage());
        }
    }

    private void executeCharge(RmsTask task) {
        TaskCommandRequest.Destination destination = task.destination();
        try {
            com.agv.navdeployer.vo.ChargeTaskVO chargeTask;
            if (destination != null) {
                task.setDestination(destination);
                chargeTask = chargeService.start(
                        new com.agv.navdeployer.service.ChargeService.ChargeCommand(
                                "rms:" + task.taskId(), null,
                                destination.x(), destination.y(), destination.yaw(),
                                targetBatteryOf(task)));
            } else {
                chargeTask = chargeService.startDirect("rms:" + task.taskId(), targetBatteryOf(task));
                log.info("rms task {} charge without destination: 原地执行充电（调度分段导航已就位）",
                        task.taskId());
            }
            task.setInternalChargeTaskId(chargeTask.id());
            registry.save(task);
            log.info("rms task {} dispatched charge task {} -> ({}, {}, {}rad, target={}%)",
                    task.taskId(), chargeTask.id(),
                    destination == null ? null : destination.x(),
                    destination == null ? null : destination.y(),
                    destination == null ? null : destination.yaw(),
                    chargeTask.targetBatteryPct());
            scheduleChargePoll(task.taskId(), chargeTask.id());
        } catch (Exception exception) {
            fail(task, RmsTaskStatus.AGV_NODE_FAILED, exception.getMessage());
        }
    }

    private Double targetBatteryOf(RmsTask task) {
        for (String key : new String[]{"target_battery_pct", "target_battery", "target_soc"}) {
            String text = task.parameterText(key);
            if (text != null && !text.isBlank()) {
                try {
                    return Double.parseDouble(text.trim());
                } catch (NumberFormatException ignored) {
                    log.warn("rms task {} parameter {}={} 不是数字，忽略", task.taskId(), key, text);
                }
            }
        }
        return null;
    }

    private void scheduleChargePoll(String taskId, Long chargeTaskId) {
        worker.schedule(() -> pollChargeTask(taskId, chargeTaskId), MOVE_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void pollChargeTask(String taskId, Long chargeTaskId) {
        RmsTask task = registry.find(taskId).orElse(null);
        if (task == null || task.status() != RmsTaskStatus.RUNNING
                || !java.util.Objects.equals(task.internalChargeTaskId(), chargeTaskId)) {
            return;
        }
        com.agv.navdeployer.vo.ChargeTaskVO chargeTask = chargeService.get(chargeTaskId);
        if (chargeTask == null) {
            fail(task, RmsTaskStatus.AGV_NODE_FAILED, "内部充电任务丢失: id=" + chargeTaskId);
            return;
        }
        switch (chargeTask.status()) {
            case "SUCCEEDED" -> {
                registry.transition(taskId, RmsTaskStatus.COMPLETED);
                resultReporter.report(registry.find(taskId).orElse(task));
            }
            case "FAILED" -> fail(task, RmsTaskStatus.AGV_NODE_FAILED,
                    chargeTask.message() == null || chargeTask.message().isBlank()
                            ? "charge task failed" : chargeTask.message());
            case "CANCELLED" -> {
                task.setMessage(chargeTask.message() == null || chargeTask.message().isBlank()
                        ? "charge task cancelled" : chargeTask.message());
                registry.transition(taskId, RmsTaskStatus.CANCELED);
                resultReporter.report(registry.find(taskId).orElse(task));
            }
            default -> scheduleChargePoll(taskId, chargeTaskId);
        }
    }

    private TaskCommandRequest.Destination resolveDestination(RmsTask task) {
        if (task.isNavigation()) {
            return task.destination();
        }
        if (task.isTemplate("return_home")) {
            RmsProperties.Pose pose = props.getBehavior().getHomePose();
            return pose == null ? task.destination()
                    : new TaskCommandRequest.Destination(pose.getX(), pose.getY(), pose.getYaw());
        }
        return task.destination();
    }

    private void scheduleMovePoll(String taskId, Long moveTaskId) {
        worker.schedule(() -> pollMoveTask(taskId, moveTaskId), MOVE_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void pollMoveTask(String taskId, Long moveTaskId) {
        RmsTask task = registry.find(taskId).orElse(null);
        if (task == null || task.status() != RmsTaskStatus.RUNNING
                || !java.util.Objects.equals(task.internalMoveTaskId(), moveTaskId)) {
            return;
        }
        String moveStatus;
        String moveError;
        try {
            MoveTaskVO moveTask = moveTaskService.get(moveTaskId);
            moveStatus = moveTask.getStatus();
            moveError = moveTask.getErrorMessage();
        } catch (Exception exception) {
            log.warn("rms task {} poll move task {} failed: {}", taskId, moveTaskId, exception.getMessage());
            scheduleMovePoll(taskId, moveTaskId);
            return;
        }
        switch (moveStatus == null ? "" : moveStatus) {
            case "SUCCEEDED" -> {
                registry.transition(taskId, RmsTaskStatus.COMPLETED);
                resultReporter.report(registry.find(taskId).orElse(task));
            }
            case "FAILED", "TIMEOUT" -> fail(task, RmsTaskStatus.AGV_NODE_FAILED,
                    moveError != null && !moveError.isBlank()
                            ? moveError : "move task " + moveStatus.toLowerCase());
            case "CANCELLED" -> {
                log.warn("rms task {} internal move task {} cancelled by external command, abort rms task",
                        taskId, moveTaskId);
                task.setMessage(moveError != null && !moveError.isBlank() ? moveError
                        : "内部移动任务 " + moveTaskId + " 被取消（非 RMS 指令），任务中止");
                registry.transition(taskId, RmsTaskStatus.CANCELED);
                resultReporter.report(registry.find(taskId).orElse(task));
            }
            default -> {
                if (!"CREATED".equals(moveStatus) && !"DISPATCHED".equals(moveStatus)
                        && !"EXECUTING".equals(moveStatus)) {
                    log.warn("rms task {} internal move task {} unexpected status {}, abort rms task",
                            taskId, moveTaskId, moveStatus);
                    fail(task, RmsTaskStatus.AGV_NODE_FAILED,
                            "内部移动任务异常状态: " + moveStatus);
                    return;
                }
                scheduleMovePoll(taskId, moveTaskId);
            }
        }
    }

    private void simulateAction(RmsTask task) {
        long dwellMs = Math.max(0L, props.getBehavior().getActionDurationMs());
        log.info("rms task {} simulated action start: template={} dwell={}ms",
                task.taskId(), task.taskTemplateType(), dwellMs);
        worker.execute(() -> {
            if (!dwell(task, dwellMs)) {
                return;
            }
            completeTask(task);
        });
    }

    private static final double ARM_PHASE_SEC = 6.0;

    private void simulateInspection(RmsTask task) {
        long dwellMs = Math.max(0L, props.getBehavior().getInspectionDurationMs());
        boolean armAvailable = armDispatcher != null && armTelemetry != null
                && armTelemetry.getJointStates() != null;
        log.info("rms task {} simulated inspection start: armSequence={} dwell={}ms",
                task.taskId(), armAvailable, dwellMs);
        worker.execute(() -> {
            if (armAvailable && runInspectionSequence(task, dwellMs)) {
                return;
            }
            if (armAvailable) {
                return;
            }
            if (!dwell(task, dwellMs)) {
                return;
            }
            completeTask(task);
        });
    }

    private boolean runInspectionSequence(RmsTask task, long dwellMs) {
        java.util.List<String> names = com.agv.navdeployer.sim.ArmStateMonitor
                .armJointNamesSorted(armTelemetry.getJointStates());
        double[] inspect = roleTargets(names, simProps.getArmInspectPose());
        double[] home = roleTargets(names,
                armStateMonitor == null ? simProps.getArmHomeLeft() : armStateMonitor.homePose());
        if (names.isEmpty() || inspect == null || home == null) {
            fail(task, RmsTaskStatus.AGV_NODE_FAILED, "质检位姿配置与关节角色不匹配（names=" + names.size() + "）");
            return true;
        }
        log.info("rms task {} inspection phase 1/3: deploy to inspect pose", task.taskId());
        if (!armPhase(task, names, "deploy(展开质检位)", inspect)) {
            return true;
        }
        if (!dwell(task, dwellMs)) {
            return false;
        }
        log.info("rms task {} inspection phase 3/3: retract to home", task.taskId());
        if (!armPhase(task, names, "retract(收拢归位)", home)) {
            return true;
        }
        completeTask(task);
        return true;
    }

    private static double[] roleTargets(java.util.List<String> names, double[] rolePose) {
        if (rolePose == null || rolePose.length < 6) {
            return null;
        }
        double[] targets = new double[names.size()];
        for (int i = 0; i < names.size(); i++) {
            int role = com.agv.navdeployer.sim.ArmStateMonitor.roleIndexOf(names.get(i));
            if (role < 0) {
                return null;
            }
            targets[i] = rolePose[role];
        }
        return targets;
    }

    private boolean armPhase(RmsTask task, java.util.List<String> names, String label, double[] targets) {
        try {
            RosCommandDispatcher.ArmMoveResult result = armDispatcher.armMove("left", targets, ARM_PHASE_SEC).join();
            if (result == null || !result.success()) {
                fail(task, RmsTaskStatus.AGV_NODE_FAILED,
                        "arm " + label + " 被拒绝: " + (result == null ? "null" : result.message()));
                return false;
            }
        } catch (Exception exception) {
            fail(task, RmsTaskStatus.AGV_NODE_FAILED, "arm " + label + " 下发失败: " + exception.getMessage());
            return false;
        }
        // 到位等待必须按墙钟给足余量，否则仿真侧还在走就被判超时（AGV_NODE_FAILED 到位超时）。
        long deadline = System.currentTimeMillis() + Math.max(
                (long) (ARM_PHASE_SEC * 1000) + 8000,
                props.getBehavior().getArmPhaseWallClockTimeoutMs());
        while (System.currentTimeMillis() < deadline) {
            if (!dwell(task, 200)) {
                return false;
            }
            SimAgvTelemetry.JointSnapshot joints = armTelemetry.getJointStates();
            if (joints == null) {
                continue;
            }
            boolean reached = true;
            for (int i = 0; i < names.size(); i++) {
                double actual = com.agv.navdeployer.sim.ArmStateMonitor.positionOf(joints, names.get(i));
                if (Math.abs(actual - targets[i]) > simProps.getArmHomeToleranceRad()) {
                    reached = false;
                    break;
                }
            }
            if (reached) {
                log.info("rms task {} arm {} reached", task.taskId(), label);
                return true;
            }
        }
        fail(task, RmsTaskStatus.AGV_NODE_FAILED, "arm " + label + " 到位超时（可能被限位截断）");
        return false;
    }

    private boolean dwell(RmsTask task, long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(Math.min(200, Math.max(1, deadline - System.currentTimeMillis())));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            RmsTask current = registry.find(task.taskId()).orElse(null);
            if (current == null || current.status() != RmsTaskStatus.RUNNING) {
                return false;
            }
        }
        return true;
    }

    private void completeTask(RmsTask task) {
        RmsTask current = registry.find(task.taskId()).orElse(null);
        if (current == null || current.status() != RmsTaskStatus.RUNNING) {
            return;
        }
        registry.transition(current.taskId(), RmsTaskStatus.COMPLETED);
        resultReporter.report(current);
    }

    private void fail(RmsTask task, RmsTaskStatus failureStatus, String message) {
        RmsTaskStatus status = failureStatus == null || !failureStatus.failed()
                ? RmsTaskStatus.FAILED : failureStatus;
        log.warn("rms task {} failed: status={} code={} msg={}",
                task.taskId(), status, status.actionStatusCode(), message);
        task.setMessage(message);
        registry.transition(task.taskId(), status);
        resultReporter.report(registry.find(task.taskId()).orElse(task));
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
