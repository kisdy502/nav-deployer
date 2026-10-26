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
    /** V2 路径规划（智能选择路线导航 vs 直线） */
    private final com.agv.navdeployer.metadata.service.RoutePlanService routePlanService;
    private final com.agv.navdeployer.metadata.mapper.MapMetaMapper v2MapMapper;
    private final com.agv.navdeployer.metadata.mapper.MapWaypointMapper v2WaypointMapper;
    /** 机器人运营状态（停用/恢复）：停用时拒绝新任务 */
    private final com.agv.navdeployer.service.RobotOperationalService operationalService;

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
                          com.agv.navdeployer.metadata.service.RoutePlanService routePlanService,
                          com.agv.navdeployer.metadata.mapper.MapMetaMapper v2MapMapper,
                          com.agv.navdeployer.metadata.mapper.MapWaypointMapper v2WaypointMapper,
                          com.agv.navdeployer.service.RobotOperationalService operationalService) {
        this.registry = registry;
        this.resultReporter = resultReporter;
        this.stateView = stateView;
        this.moveTaskService = moveTaskService;
        this.props = props;
        this.armStateMonitor = armStateMonitor;
        this.armDispatcher = armDispatcher;
        this.armTelemetry = armTelemetry;
        this.simProps = simProps;
        this.routePlanService = routePlanService;
        this.v2MapMapper = v2MapMapper;
        this.v2WaypointMapper = v2WaypointMapper;
        this.operationalService = operationalService;
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
        cancelInternalMove(task);
        resultReporter.report(task);
        return BodyReply.success("stopped", task.toInfoReport());
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
        // 行为树 action 任务（fetch_cable 等）：协议不携带 destination，占位仿真执行
        if (task.isArmGateExempt()) {
            simulateAction(task);
            return;
        }
        // navigate / charge / replace_battery / return_home 统一走移动
        TaskCommandRequest.Destination destination = resolveDestination(task);
        if (destination == null) {
            fail(task, RmsTaskStatus.AGV_NODE_FAILED, "no destination for task (template=" + task.taskTemplateType()
                    + ", charge/home pose 未配置且 parameters.destination 缺失)");
            return;
        }
        task.setDestination(destination);
        try {
            MoveTaskVO moveTask = createOptimizedMoveTask(taskId, destination);
            task.setInternalMoveTaskId(moveTask.getId());
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
     * 智能导航：RMS 只给目标坐标，上位机自主选择最优执行方式。
     * 优先尝试路线导航（V2 已部署路线图 Dijkstra 规划，走曲线），不可达回落直线 GOAL。
     * 不改变 RMS 接口，对上层透明。
     */
    private MoveTaskVO createOptimizedMoveTask(String taskId, TaskCommandRequest.Destination destination) {
        // 1. 尝试路线导航
        MoveTaskVO routeTask = tryRouteNavigation(taskId, destination);
        if (routeTask != null) {
            return routeTask;
        }
        // 2. 回落直线
        return moveTaskService.create(new MoveTaskCreateDTO(
                "GOAL", null, null,
                destination.x(), destination.y(), destination.yaw(),
                null, Boolean.TRUE));
    }

    /** 目标点位与机器人最近点位之间的匹配距离阈值（米） */
    private static final double WAYPOINT_MATCH_RADIUS = 1.5;

    /**
     * 路线导航尝试：找当前 PUBLISHED 地图 → 匹配目标/机器人最近点位 → Dijkstra 规划。
     * 规划结果为单条路线时用 FOLLOW_PATH（走曲线），多条时也用 FOLLOW_PATH（取首段），
     * 无可路线返回 null 回落 GOAL。
     */
    private MoveTaskVO tryRouteNavigation(String taskId, TaskCommandRequest.Destination destination) {
        try {
            // 当前 PUBLISHED 的 V2 地图
            var publishedMap = v2MapMapper.selectOne(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(
                                    com.agv.navdeployer.metadata.entity.MapMeta.class)
                            .eq(com.agv.navdeployer.metadata.entity.MapMeta::getStatus,
                                    com.agv.navdeployer.metadata.entity.MapMeta.STATUS_PUBLISHED)
                            .last("LIMIT 1"));
            if (publishedMap == null) {
                return null;
            }
            // 加载该地图全部点位
            var waypoints = v2WaypointMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(
                                    com.agv.navdeployer.metadata.entity.MapWaypoint.class)
                            .eq(com.agv.navdeployer.metadata.entity.MapWaypoint::getMapId,
                                    publishedMap.getId()));
            if (waypoints.isEmpty()) {
                return null;
            }
            // 目标最近点位
            var targetWp = nearestWaypoint(waypoints, destination.x(), destination.y());
            if (targetWp == null || targetWp.distance > WAYPOINT_MATCH_RADIUS) {
                log.debug("rms navigate: target ({},{}) not near any waypoint, fallback GOAL",
                        destination.x(), destination.y());
                return null;
            }
            // 机器人当前位姿 → 最近点位
            var pose = armTelemetry.getMapPose();
            if (pose == null) {
                return null;
            }
            var robotWp = nearestWaypoint(waypoints, pose.x(), pose.y());
            if (robotWp == null || robotWp.distance > WAYPOINT_MATCH_RADIUS * 2) {
                return null; // 机器人不在任何点位附近，无法路线导航
            }
            // Dijkstra 路径规划
            var plan = routePlanService.plan(publishedMap.getId(), robotWp.waypoint.getId(), targetWp.waypoint.getId());
            if (!plan.found() || plan.steps().isEmpty()) {
                log.debug("rms navigate: no route from {} to {}, fallback GOAL",
                        robotWp.waypoint.getWaypointCode(), targetWp.waypoint.getWaypointCode());
                return null;
            }
            // 提取不重复的路线 ID
            var pathIds = plan.steps().stream()
                    .filter(s -> !s.isCrossFloor() && s.pathId() > 0)
                    .map(com.agv.navdeployer.metadata.service.RoutePlanService.RouteStep::pathId)
                    .distinct()
                    .toList();
            if (pathIds.isEmpty()) {
                return null;
            }
            // 单条或多条路线都取第一条执行（机器人会走完整条链，多段中转由前端后续触发）
            Long pathId = pathIds.get(0);
            log.info("rms task {} using route navigation: pathId={}, steps={}, totalCost={}s",
                    taskId, pathId, plan.steps().size(), String.format("%.1f", plan.totalCost()));
            return moveTaskService.create(new MoveTaskCreateDTO(
                    "FOLLOW_PATH", null, pathId,
                    null, null, null, null, null));
        } catch (Exception e) {
            log.debug("rms route navigation failed, fallback GOAL: {}", e.getMessage());
            return null;
        }
    }

    private record NearestWaypoint(com.agv.navdeployer.metadata.entity.MapWaypoint waypoint, double distance) {
    }

    private static NearestWaypoint nearestWaypoint(
            List<com.agv.navdeployer.metadata.entity.MapWaypoint> waypoints, double x, double y) {
        com.agv.navdeployer.metadata.entity.MapWaypoint best = null;
        double bestDist = Double.MAX_VALUE;
        for (var wp : waypoints) {
            double dist = Math.hypot(wp.getX() - x, wp.getY() - y);
            if (dist < bestDist) {
                best = wp;
                bestDist = dist;
            }
        }
        return best != null ? new NearestWaypoint(best, bestDist) : null;
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
            default -> scheduleMovePoll(taskId, moveTaskId);
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
        long deadline = System.currentTimeMillis() + (long) (ARM_PHASE_SEC * 1000) + 8000;
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
        if (moveTaskId == null) {
            return;
        }
        try {
            moveTaskService.cancel(moveTaskId);
        } catch (Exception exception) {
            // 已完成/已取消的内部任务会抛异常，属正常
            log.debug("cancel internal move task {} for rms task {}: {}",
                    moveTaskId, task.taskId(), exception.getMessage());
        }
    }

    private void logRmsCommand(String command, RmsTask task) {
        log.info("RMS 指令 {} task_id={} action_id={} template={} type={} parameters={}",
                command, task.taskId(), task.actionId(),
                 task.taskTemplateType(), task.taskType(), task.parameters());
    }

}
