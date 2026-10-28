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

/**
 * RMS 任务指令处理（防烂铁律③：只编排，不执行——移动全部委托 MoveTaskService）。
 *
 * <p>生命周期：idle →(start) running →(完成/失败) completed/failed → result_report；
 * pause = 取消内部移动并置 paused；resume = 按任务目标快照重新下发移动（降级语义，
 * 见 docs/rms-integration.md §3.3）；stop = 取消并置 canceled。
 */
@Component
public class RmsTaskService {

    private static final Logger log = LoggerFactory.getLogger(RmsTaskService.class);
    private static final long MOVE_POLL_INTERVAL_MS = 500L;

    private final RmsTaskRegistry registry;
    private final RmsResultReporter resultReporter;
    private final RobotStateView stateView;
    private final MoveTaskService moveTaskService;
    private final RmsProperties props;
    /** 机械臂归位联锁（可空：独立装配无此 Bean 时跳过受理闸门，MoveTaskService 闸门仍兜底） */
    private final com.agv.navdeployer.sim.ArmStateMonitor armStateMonitor;
    /** 机械臂指令通道与遥测（质检三阶段序列用；可空=无臂环境退化为纯停留模拟） */
    private final com.agv.navdeployer.sim.RosCommandDispatcher armDispatcher;
    private final com.agv.navdeployer.sim.SimAgvTelemetry armTelemetry;
    private final com.agv.navdeployer.sim.SimAgvProperties simProps;
    /** 智能导航（路线优先，回落直线；navigate / return_home 用） */
    private final com.agv.navdeployer.service.SmartNavigationService smartNavigation;
    /** 充电任务编排（charge / replace_battery 用；与前端「去充电」共用底层链路） */
    private final com.agv.navdeployer.service.ChargeService chargeService;
    /** 机器人运营状态（停用/恢复）：停用时拒绝新任务 */
    private final com.agv.navdeployer.service.RobotOperationalService operationalService;
    /** 地图一致性闸门（可空：独立装配无此 Bean 时跳过；机器人地图 vs 导航基准图，不一致时错误透传 RMS） */
    private final com.agv.navdeployer.service.MapAlignmentGuard mapAlignmentGuard;

    /** 执行与轮询线程：与 RMS query 回调线程隔离，保证 queryable 快速回复 */
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "rms-task-worker");
                thread.setDaemon(true);
                return thread;
            });

    /** task_template/add 注册的自定义模板（template_code → 元数据）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>> customTemplates =
            new java.util.concurrent.ConcurrentHashMap<>();

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
                           com.agv.navdeployer.service.MapAlignmentGuard mapAlignmentGuard) {
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
    }

    /** 地图一致性前置闸门：机器人地图 ≠ 导航基准图时拒绝并透传原因（同名点位坐标错位会乱撞）。 */
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

    /** 移动类任务（navigate/charge/replace_battery/return_home）的机械臂归位闸门：行为树 action 任务不拦。 */
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

    // ==================== queryable 入口（同步快速返回） ====================

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

    /** task_template/add：注册自定义模板（template_code 唯一键）。 */
    public BodyReply addTemplate(TaskCommandRequest request) {
        String code = request.templateCode() != null ? request.templateCode()
                : request.parameterText("template_code");
        if (code == null || code.isBlank()) {
            return BodyReply.failure(400, "template_code must not be blank");
        }
        customTemplates.put(code, Map.of(
                "templates_id", request.templateId() != null ? request.templateId() : code,
                "template_code", code,
                "template_description", request.parameterText("template_description") == null
                        ? "" : request.parameterText("template_description"),
                "template_add_time", java.time.OffsetDateTime.now().toString()));
        log.info("RMS task_template/add: code={}", code);
        return BodyReply.success("template add success", Map.of("template_code", code));
    }

    /** task_template/delete：按 template_code 或 template_id 删除（信息从 Base Key 传递）。 */
    public BodyReply deleteTemplate(String templateCode) {
        if (templateCode == null || templateCode.isBlank()) {
            return BodyReply.failure(400, "template_code must not be blank");
        }
        boolean removed = customTemplates.remove(templateCode) != null;
        log.info("RMS task_template/delete: code={} removed={}", templateCode, removed);
        return BodyReply.success("template delete " + (removed ? "success" : "not found"),
                Map.of("template_code", templateCode));
    }

    /** 覆盖后的模板查询：自定义模板 + 内置模板。 */
    private List<Map<String, String>> allTemplates() {
        List<Map<String, String>> all = new java.util.ArrayList<>(builtinTemplates());
        customTemplates.values().forEach(template -> {
            Map<String, String> entry = new java.util.LinkedHashMap<>();
            template.forEach((key, value) -> entry.put(key, String.valueOf(value)));
            all.add(entry);
        });
        return all;
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
            // 同 ID 在途时拒绝覆盖：旧实例的执行仍在跑，静默替换会留下孤儿 move task
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
        // 停用校验：维修中/退役的机器人拒绝新任务
        if (operationalService.isDecommissioned()) {
            log.warn("rms task {} rejected: robot decommissioned ({})", request.taskId(), operationalService.getReason());
            return BodyReply.failure(503,
                    "robot is decommissioned (" + operationalService.getReason() + "), not accepting tasks");
        }
        RmsTask task = registry.find(request.taskId()).orElse(null);
        if (task == null || task.status().terminal()) {
            // RMS 多阶段作业复用 task_id（质检作业 = 先导航后质检，两阶段同 ID）：
            // 旧实例已终态时按新阶段的模板/参数重建任务；首次直达 start（免 add）同路径
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
        logRmsCommand("task/start", task);
        registry.transition(task.taskId(), RmsTaskStatus.RUNNING);
        final String startedTaskId = task.taskId();
        // 执行异步化：queryable 回调立即返回 running，结果经 result_report 回流
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

    // ==================== 运维查询/复位（任务管理页 REST 用） ====================

    /** 唯一在途任务（RUNNING/PAUSED），无则 null。 */
    public RmsTaskVO activeTaskView() {
        RmsTask active = registry.activeTask();
        return active == null ? null : RmsTaskVO.from(active);
    }

    /** 执行历史（DB 记录，新→旧；含本会话与跨重启）。 */
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

    /**
     * 人工复位：把所有非终态任务强制收口为 canceled（取消内部移动/充电、上报 RMS），
     * 释放 busy 闸门让上层可继续下发新任务。适用：任务卡中间态、状态错乱自愈。
     */
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

    // ==================== 异步执行（worker 线程） ====================

    private void execute(String taskId) {
        RmsTask task = registry.find(taskId).orElse(null);
        if (task == null || task.status() != RmsTaskStatus.RUNNING) {
            return;
        }
        if (task.isInspectionAction()) {
            simulateInspection(task);
            return;
        }
        // 行为树 action 任务（fetch_cable 取放线束等）：协议不携带 destination，
        // 占位仿真执行（固定延时后 COMPLETED，仅上报简单状态信封，无 result_report）
        if (task.isArmGateExempt()) {
            simulateAction(task);
            return;
        }
        // charge / replace_battery：走充电任务编排（导航到桩 → 开始充电 → 充到目标电量），
        // 与前端「去充电」共用 ChargeService 底层链路
        if (task.isTemplate("charge") || task.isTemplate("replace_battery")) {
            executeCharge(task);
            return;
        }
        // navigate / return_home 统一走移动
        TaskCommandRequest.Destination destination = resolveDestination(task);
        if (destination == null) {
            fail(task, RmsTaskStatus.AGV_NODE_FAILED, "no destination for task (template=" + task.taskTemplateType()
                    + ", charge/home pose 未配置且 parameters.destination 缺失)");
            return;
        }
        task.setDestination(destination);
        try {
            MoveTaskVO moveTask = smartNavigation.createOptimizedMoveTask(taskId,
                    destination.x(), destination.y(), destination.yaw());
            task.setInternalMoveTaskId(moveTask.getId());
            registry.save(task);
            log.info("rms task {} dispatched internal move task {} (type={}) -> ({}, {}, {}rad)",
                    taskId, moveTask.getId(), moveTask.getTaskType(),
                    destination.x(), destination.y(), destination.yaw());
            scheduleMovePoll(taskId, moveTask.getId());
        } catch (Exception exception) {
            // MoveTaskService 的闸门（连接/定位/模式/占用）在这里拦截
            fail(task, RmsTaskStatus.AGV_NODE_FAILED, exception.getMessage());
        }
    }

    /**
     * 充电任务执行：目标 = charge-pose 配置（缺省回退报文 destination），
     * 目标电量 = parameters.target_battery_pct（缺省用 charge.target_battery_pct 配置，默认 100）。
     */
    private void executeCharge(RmsTask task) {
        TaskCommandRequest.Destination destination = resolveDestination(task);
        if (destination == null) {
            fail(task, RmsTaskStatus.AGV_NODE_FAILED, "no destination for charge task（charge-pose 未配置且 parameters.destination 缺失）");
            return;
        }
        task.setDestination(destination);
        try {
            com.agv.navdeployer.vo.ChargeTaskVO chargeTask = chargeService.start(
                    new com.agv.navdeployer.service.ChargeService.ChargeCommand(
                            "rms:" + task.taskId(), null,
                            destination.x(), destination.y(), destination.yaw(),
                            targetBatteryOf(task)));
            task.setInternalChargeTaskId(chargeTask.id());
            registry.save(task);
            log.info("rms task {} dispatched charge task {} -> ({}, {}, {}rad, target={}%)",
                    task.taskId(), chargeTask.id(), destination.x(), destination.y(),
                    destination.yaw(), chargeTask.targetBatteryPct());
            scheduleChargePoll(task.taskId(), chargeTask.id());
        } catch (Exception exception) {
            // ChargeService/MoveTaskService 的闸门（连接/定位/模式/占用/已在充电）在这里拦截
            fail(task, RmsTaskStatus.AGV_NODE_FAILED, exception.getMessage());
        }
    }

    /** RMS 充电目标电量：parameters.target_battery_pct / target_battery / target_soc（可缺省）。 */
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
        // 任务被 pause/stop/删除：静默停止轮询（终态迁移由指令入口完成）
        if (task == null || task.status() != RmsTaskStatus.RUNNING
                || !java.util.Objects.equals(task.internalChargeTaskId(), chargeTaskId)) {
            return;
        }
        com.agv.navdeployer.vo.ChargeTaskVO chargeTask = chargeService.get(chargeTaskId);
        if (chargeTask == null) {
            // 充电任务实例被覆盖/消失（理论不该发生）：按失败收口
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
                // 充电被打断/取消但 RMS 任务仍在跑（如新移动任务顶掉、底层手动取消）
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
        if (task.isTemplate("charge") || task.isTemplate("replace_battery")) {
            RmsProperties.Pose pose = props.getBehavior().getChargePose();
            return pose == null ? task.destination()
                    : new TaskCommandRequest.Destination(pose.getX(), pose.getY(), pose.getYaw());
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
        // 任务被 pause/stop/删除：静默停止轮询（终态迁移由指令入口完成）
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
                // 内部移动被取消但 RMS 任务仍在跑（前端手动取消/被抢占；pause/stop 路径不会走到这里）：
                // 必须收口上报，否则任务永远 RUNNING，后续所有 task/start 被 busy 闸门拒绝
                log.warn("rms task {} internal move task {} cancelled by external command, abort rms task",
                        taskId, moveTaskId);
                task.setMessage(moveError != null && !moveError.isBlank() ? moveError
                        : "内部移动任务 " + moveTaskId + " 被取消（非 RMS 指令），任务中止");
                registry.transition(taskId, RmsTaskStatus.CANCELED);
                resultReporter.report(registry.find(taskId).orElse(task));
            }
            default -> {
                // 防御：非活跃状态一律收口（避免未知终态导致无限轮询），活跃状态继续等
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

    /** 行为树 action 占位执行（fetch_cable 等无专用执行器的模板）：固定时长后完成，不移动。 */
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

    /** 质检占位执行：固定时长后完成（仿真机械臂接入后替换此方法）。 */
    /**
     * 质检作业仿真——单任务三阶段（对齐真机本体程序语义，云端只见一个 action 任务）：
     * ① 展臂到质检位（arm_move + 到位等待）② 采集停留 ③ 收臂归位（arm_move + 到位等待）；
     * 全部完成后 COMPLETED 并走既有 result_report → 上传 zip 闭环。
     * 无臂环境（dispatcher/关节数据缺失）退化为纯停留模拟（保留旧行为）；
     * 序列期间臂为 DEPLOYED/MOVING，移动联锁自动拒绝导航——正是真机安全语义。
     */
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
                return; // 序列内已 fail 上报
            }
            // 无臂退化：纯停留
            if (!dwell(task, dwellMs)) {
                return;
            }
            completeTask(task);
        });
    }

    /** @return true=序列已收尾（成功或已 fail 上报）；false=任务被暂停/取消（各自已上报） */
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

    /** 角色顺序位姿 → 关节名排序顺序目标角；任一关节角色无法识别返回 null。 */
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

    /** 单个臂阶段：下发 arm_move → 轮询到位（容差同联锁）；拒绝/超时 → 任务 FAILED。 */
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
        // arm_move 时长是仿真时间；宿主机过载时 Gazebo RTF<1（如 0.3 时 6 仿真秒≈20 墙钟秒），
        // 到位等待必须按墙钟给足余量，否则仿真侧还在走就被判超时（AGV_NODE_FAILED 到位超时）。
        long deadline = System.currentTimeMillis() + Math.max(
                (long) (ARM_PHASE_SEC * 1000) + 8000,
                props.getBehavior().getArmPhaseWallClockTimeoutMs());
        while (System.currentTimeMillis() < deadline) {
            if (!dwell(task, 200)) {
                return false; // 暂停/取消
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

    /** 停留/等待：分片睡眠并检查任务仍 RUNNING（暂停/取消即中止，由对应入口上报）。 */
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
                // 已完成/已取消的内部任务会抛异常，属正常
                log.debug("cancel internal move task {} for rms task {}: {}",
                        moveTaskId, task.taskId(), exception.getMessage());
            }
        }
        Long chargeTaskId = task.internalChargeTaskId();
        if (chargeTaskId != null) {
            try {
                chargeService.cancel("rms task pause/stop: " + task.taskId());
            } catch (Exception exception) {
                // 充电任务已终态时抛异常，属正常
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
