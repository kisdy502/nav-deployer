package com.agv.navdeployer.task.application;

import com.agv.navdeployer.common.error.BusinessException;
import com.agv.navdeployer.map.application.MapMetaService;
import com.agv.navdeployer.map.domain.MapMeta;
import com.agv.navdeployer.map.domain.MapPath;
import com.agv.navdeployer.map.domain.MapPathSegment;
import com.agv.navdeployer.map.domain.MapWaypoint;
import com.agv.navdeployer.task.application.cmd.MoveTaskCreateCmd;
import com.agv.navdeployer.task.application.dto.MoveTaskDTO;
import com.agv.navdeployer.task.domain.movetask.MoveTask;
import com.agv.navdeployer.task.domain.movetask.MoveTaskSegment;
import com.agv.navdeployer.task.domain.movetask.TaskErrorCode;
import com.agv.navdeployer.task.domain.port.FollowControlPoint;
import com.agv.navdeployer.task.domain.port.FollowEdgeCommand;
import com.agv.navdeployer.task.domain.port.FollowEdgeListener;
import com.agv.navdeployer.task.domain.port.RobotCommandPort;
import com.agv.navdeployer.task.domain.port.RobotConnectionPort;
import com.agv.navdeployer.task.domain.port.RobotEventPort;
import com.agv.navdeployer.task.domain.port.RobotPose;
import com.agv.navdeployer.task.domain.port.RobotTelemetryPort;
import com.agv.navdeployer.task.domain.port.RobotTuning;
import com.agv.navdeployer.task.domain.repository.MoveTaskRepository;
import com.agv.navdeployer.task.infrastructure.mapstruct.MoveTaskMapstruct;
import com.agv.navdeployer.task.infrastructure.robot.RobotTuningProvider;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MoveTaskService {

    private static final Logger log = LoggerFactory.getLogger(MoveTaskService.class);
    private static final long FEEDBACK_DB_WRITE_INTERVAL_MS = 1000L;
    private static final long FORCE_FINALIZE_DELAY_MS = 15000L;
    // 与 SmartNavigationService 保持一致：目标/机器人位置与最近点位的匹配半径
    private static final double WAYPOINT_MATCH_RADIUS = 1.5;

    private final MoveTaskRepository moveTaskRepository;
    private final MoveTaskMapstruct moveTaskMapstruct;
    private final RobotConnectionPort robotConnection;
    private final RobotCommandPort robotCommands;
    private final RobotTelemetryPort telemetry;
    private final RobotEventPort events;
    private final RobotTuning tuning;
    private final MapMetaService mapMetaService;
    private final RobotOperationalService operationalService;
    private final MapAlignmentGuard mapAlignmentGuard;
    private final com.agv.navdeployer.task.domain.charge.ChargeProperties chargeProps;
    private final com.agv.navdeployer.task.infrastructure.repository.mapper.MoveTaskSegmentMapper segmentMapper;
    private final com.agv.navdeployer.map.application.RoutePlanService routePlanService;

    // 单线程 worker 串行化任务启动；任务先落库再下发，防 result 回调早于事务可见
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "move-task-worker");
                thread.setDaemon(true);
                return thread;
            });
    private final Map<Long, TaskRuntime> activeRuntimes = new ConcurrentHashMap<>();

    public MoveTaskService(MoveTaskRepository moveTaskRepository,
                           MoveTaskMapstruct moveTaskMapstruct,
                           RobotConnectionPort robotConnection,
                           RobotCommandPort robotCommands,
                           RobotTelemetryPort telemetry,
                           RobotEventPort events,
                           RobotTuningProvider tuningProvider,
                           MapMetaService mapMetaService,
                           RobotOperationalService operationalService,
                           MapAlignmentGuard mapAlignmentGuard,
                           com.agv.navdeployer.task.domain.charge.ChargeProperties chargeProps,
                           com.agv.navdeployer.task.infrastructure.repository.mapper.MoveTaskSegmentMapper segmentMapper,
                           com.agv.navdeployer.map.application.RoutePlanService routePlanService) {
        this.moveTaskRepository = moveTaskRepository;
        this.moveTaskMapstruct = moveTaskMapstruct;
        this.robotConnection = robotConnection;
        this.robotCommands = robotCommands;
        this.telemetry = telemetry;
        this.events = events;
        this.tuning = tuningProvider.tuning();
        this.mapMetaService = mapMetaService;
        this.operationalService = operationalService;
        this.mapAlignmentGuard = mapAlignmentGuard;
        this.chargeProps = chargeProps;
        this.segmentMapper = segmentMapper;
        this.routePlanService = routePlanService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleTasks() {
        int recovered = moveTaskRepository.markStaleInterrupted("服务重启，任务中断");
        if (recovered > 0) {
            log.warn("recovered {} stale tasks after restart", recovered);
        }
        reapZombieTasks();
    }

    /**
     * 僵尸任务巡检（每分钟）：库中非终态 + 内存无 runtime + 超时未更新 → 强制 FAILED。
     * 兜底收尾链路异常（如迟到事务覆盖终态）导致的 DISPATCHED 僵尸——它会卡死 RMS busy 锁。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void reapZombieTasks() {
        try {
            java.time.LocalDateTime staleBefore = java.time.LocalDateTime.now().minusMinutes(5);
            for (MoveTask task : moveTaskRepository.findNonTerminal()) {
                if (activeRuntimes.containsKey(task.getId())) {
                    continue;
                }
                if (task.getUpdatedAt() != null && task.getUpdatedAt().isAfter(staleBefore)) {
                    continue;
                }
                task.markFinished(MoveTask.STATUS_FAILED,
                        "僵尸任务清理：运行时已丢失且超过 5 分钟无更新，强制终结（防 busy 锁死）");
                moveTaskRepository.update(task);
                log.warn("zombie move task reaped: id={}, stale since {}", task.getId(), task.getUpdatedAt());
                publishTaskEvent(task);
            }
        } catch (Exception exception) {
            log.debug("zombie reap failed: {}", exception.getMessage());
        }
    }

    public MoveTaskDTO create(MoveTaskCreateCmd dto) {
        return create(dto, "manual");
    }

    public boolean hasActiveTask() {
        return !activeRuntimes.isEmpty();
    }

    private void interruptChargingIfAny() {
        var status = telemetry.status();
        if (status == null || !Boolean.TRUE.equals(status.charging())) {
            return;
        }
        log.info("move task creation while charging: interrupt charging first (set_charging=false)");
        try {
            com.fasterxml.jackson.databind.node.ObjectNode args =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            args.put("data", false);
            robotCommands.callService(chargeProps.getService(), args, chargeProps.getServiceTimeoutMs())
                    .whenComplete((values, error) -> {
                        if (error != null) {
                            log.warn("auto interrupt charging failed: {}", error.getMessage());
                        }
                    });
        } catch (Exception exception) {
            log.warn("auto interrupt charging dispatch failed: {}", exception.getMessage());
        }
    }

    public MoveTaskDTO create(MoveTaskCreateCmd dto, String source) {
        if (operationalService.isDecommissioned()) {
            throw new IllegalStateException(
                    "机器人已停用（" + operationalService.getReason() + "），无法下发移动任务，请先点击「启动」恢复");
        }
        if (!activeRuntimes.isEmpty()) {
            throw new IllegalStateException("已有在途任务（" + activeRuntimes.size() + " 条），机器人同一时刻只允许一个 goal，请先取消或等待完成");
        }
        if (!robotConnection.isConnected()) {
            throw new IllegalStateException("机器人连接未就绪，无法下发移动任务");
        }
        var status = telemetry.status();
        if (status == null || !status.poseInitialized()) {
            throw new IllegalStateException("机器人定位未收敛（pose_initialized=false），禁止下发导航指令");
        }
        if (telemetry.statusFresh() && status.mode() != null
                && !"NAVIGATION".equals(status.mode())) {
            throw new IllegalStateException("机器人 mode=" + status.mode()
                    + "（切图/建图中），禁止下发导航指令，请等待回到 NAVIGATION");
        }
        if (!telemetry.armMovementAllowed()) {
            throw new IllegalStateException("机械臂未归位（arm_state=" + telemetry.armState()
                    + "），禁止移动作业，请先收臂归位");
        }
        if (mapAlignmentGuard != null) {
            mapAlignmentGuard.requireAligned();
        }
        interruptChargingIfAny();

        MoveTask task = new MoveTask();
        task.setTaskNo("move-" + UUID.randomUUID());
        task.setTaskType(dto.taskType());
        task.setStatus(MoveTask.STATUS_CREATED);
        task.setSource(source == null || source.isBlank() ? "manual" : source);
        List<SegmentPlan> segments;
        boolean endPointSingle;

        switch (dto.taskType()) {
            case MoveTask.TYPE_TO_WAYPOINT -> {
                if (dto.waypointId() == null) {
                    throw new IllegalArgumentException("TO_WAYPOINT 任务必须携带 waypoint_id");
                }
                MapWaypoint waypoint = mapMetaService.getWaypoint(dto.waypointId());
                if (waypoint == null) {
                    throw new BusinessException(TaskErrorCode.WAYPOINT_NOT_FOUND, "点位 " + dto.waypointId() + " 不存在");
                }
                MapMeta published = mapMetaService.findPublished();
                if (published == null) {
                    throw new IllegalStateException("当前无 PUBLISHED 状态的 V2 地图，无法执行点位导航");
                }
                if (!published.getId().equals(waypoint.getMapId())) {
                    throw new IllegalStateException("点位 " + waypoint.getWaypointCode()
                            + " 不属于当前 PUBLISHED 地图「" + published.getMapName() + "」");
                }
                task.setWaypointId(waypoint.getId());
                task.setGoalX(waypoint.getX());
                task.setGoalY(waypoint.getY());
                task.setGoalTheta(waypoint.getYaw());
                task.setRouteSummary("→ " + waypoint.getWaypointCode());
                segments = List.of(new SegmentPlan("", waypoint.getWaypointCode(),
                        waypoint.getX(), waypoint.getY(), waypoint.getYaw(),
                        "STRAIGHT", List.of(), dto.maxSpeed(), false, false,
                        Double.NaN, Double.NaN, Double.NaN));
                endPointSingle = dto.endPoint() == null || dto.endPoint();
            }
            case MoveTask.TYPE_GOAL -> {
                if (dto.x() == null || dto.y() == null || dto.theta() == null) {
                    throw new IllegalArgumentException("GOAL 任务必须携带 x / y / theta");
                }
                task.setGoalX(dto.x());
                task.setGoalY(dto.y());
                task.setGoalTheta(dto.theta());
                task.setRouteSummary(String.format(java.util.Locale.ROOT, "→ (%.2f, %.2f)", dto.x(), dto.y()));
                segments = List.of(new SegmentPlan("", "", dto.x(), dto.y(), dto.theta(),
                        "STRAIGHT", List.of(), dto.maxSpeed(), false, false,
                        Double.NaN, Double.NaN, Double.NaN));
                endPointSingle = dto.endPoint() == null || dto.endPoint();
            }
            case MoveTask.TYPE_FREE -> {
                if (dto.x() == null || dto.y() == null) {
                    throw new IllegalArgumentException("FREE 任务必须携带 x / y");
                }
                double theta = dto.theta() != null ? dto.theta() : 0.0;
                task.setGoalX(dto.x());
                task.setGoalY(dto.y());
                task.setGoalTheta(theta);
                segments = List.of(new SegmentPlan("", "", dto.x(), dto.y(), theta,
                        "FREE", List.of(), dto.maxSpeed(), false, false,
                        Double.NaN, Double.NaN, Double.NaN));
                endPointSingle = true;
            }
            case MoveTask.TYPE_FOLLOW_PATH -> {
                if (dto.pathId() == null && (dto.chain() == null || dto.chain().isEmpty())) {
                    throw new IllegalArgumentException("FOLLOW_PATH 任务必须携带 path_id 或 chain");
                }
                boolean chained = dto.chain() != null && !dto.chain().isEmpty();
                Long headPathId = chained ? dto.chain().get(0).pathId() : dto.pathId();
                requireDeployedPath(headPathId);
                List<SegmentPlan> chain = new java.util.ArrayList<>();
                if (chained) {
                    for (var leg : dto.chain()) {
                        requireDeployedPath(leg.pathId());
                        chain.addAll(buildOrientedLegPlan(leg.pathId(), leg.fromCode(), leg.toCode(), dto.maxSpeed()));
                    }
                    segments = chain;
                    log.info("FOLLOW_PATH 跨路线链: legs={}, segments={}, paths={}",
                            dto.chain().size(), segments.size(),
                            dto.chain().stream().map(l -> String.valueOf(l.pathId())).toList());
                } else {
                    segments = buildPathPlan(dto.pathId(), dto.maxSpeed());
                }
                if (segments.isEmpty()) {
                    throw new IllegalStateException("路线 " + headPathId + " 没有可执行的边");
                }
                RobotPose pose = telemetry.mapPose();
                if (!chained && pose != null && shouldReverseForNearestEnd(segments, pose.x(), pose.y())) {
                    SegmentPlan firstEdge = segments.get(0);
                    SegmentPlan lastEdge = segments.get(segments.size() - 1);
                    segments = reversePathPlan(segments);
                    log.info("follow path {} reversed for nearest end: robot=({},{}) start=({},{}) end=({},{})",
                            dto.pathId(), pose.x(), pose.y(),
                            firstEdge.sx(), firstEdge.sy(), lastEdge.x(), lastEdge.y());
                }
                int edgeCount = segments.size();
                segments = withStartHopIfNeeded(segments,
                        pose == null ? null : pose.x(), pose == null ? null : pose.y(),
                        tuning.followPathStartRadius());
                if (segments.size() > edgeCount && pose != null) {
                    SegmentPlan firstEdge = segments.get(1);
                    log.info("follow path {} start hop prepended: robot=({},{}) start=({},{}), distance={}m > {}m",
                            dto.pathId(), pose.x(), pose.y(), firstEdge.sx(), firstEdge.sy(),
                            String.format("%.2f", Math.hypot(pose.x() - firstEdge.sx(), pose.y() - firstEdge.sy())),
                            tuning.followPathStartRadius());
                }
                SegmentPlan last = segments.get(segments.size() - 1);
                task.setPathId(headPathId);
                task.setGoalX(last.x());
                task.setGoalY(last.y());
                task.setGoalTheta(last.theta());
                task.setRouteSummary(routeChainOf(segments));
                endPointSingle = true;
            }
            default -> throw new IllegalArgumentException("不支持的 task_type: " + dto.taskType());
        }

        task.setSegmentTotal(segments.size());
        moveTaskRepository.insert(task);

        TaskRuntime runtime = new TaskRuntime(task, segments, endPointSingle);
        activeRuntimes.put(task.getId(), runtime);
        runtime.watchdog = worker.schedule(() -> onWatchdog(runtime),
                tuning.goalTimeoutSeconds(), TimeUnit.SECONDS);
        dispatchSegment(runtime, 1);
        log.info("move task created: id={}, no={}, type={}, segments={}",
                task.getId(), task.getTaskNo(), task.getTaskType(), segments.size());
        publishTaskEvent(task);
        return moveTaskMapstruct.toDTO(task);
    }

    public MoveTaskDTO cancel(Long id) {
        TaskRuntime runtime = activeRuntimes.get(id);
        if (runtime == null) {
            throw new IllegalStateException("任务 " + id + " 不在执行中，无法取消");
        }
        if (MoveTask.STATUS_PAUSED.equals(runtime.task.getStatus())) {
            finalizeTask(runtime, MoveTask.STATUS_CANCELLED, "暂停中取消");
            return moveTaskMapstruct.toDTO(runtime.task);
        }
        runtime.cancelRequested = true;
        robotCommands.cancelGoal(runtime.currentGoalId);
        scheduleForceFinalize(runtime, MoveTask.STATUS_CANCELLED, "已下发取消，但未收到 action_result，强制终结");
        log.info("move task cancel requested: id={}, goal={}", id, runtime.currentGoalId);
        return moveTaskMapstruct.toDTO(runtime.task);
    }

    /**
     * 立即暂停：cancel_goal 中断当前段，机器人就地停住；runtime 保留（占用 goal 槽，阻止新任务插队），
     * 恢复时从当前位置重规划到原目标（剩余段作废，段内无中间续点）。
     */
    public MoveTaskDTO pause(Long id) {
        TaskRuntime runtime = activeRuntimes.get(id);
        if (runtime == null) {
            throw new IllegalStateException("任务 " + id + " 不在执行中，无法暂停");
        }
        if (MoveTask.STATUS_PAUSED.equals(runtime.task.getStatus()) || runtime.pauseRequested) {
            return moveTaskMapstruct.toDTO(runtime.task);
        }
        runtime.pauseRequested = true;
        try {
            robotCommands.cancelGoal(runtime.currentGoalId);
        } catch (Exception exception) {
            log.warn("pause cancel_goal dispatch failed: id={}, {}", id, exception.getMessage());
        }
        schedulePauseGuard(runtime);
        log.info("move task pause requested: id={}, goal={}, seq={}/{}, robot=({},{})",
                id, runtime.currentGoalId, runtime.currentSeq, runtime.segments.size(),
                runtime.task.getCurrentX(), runtime.task.getCurrentY());
        return moveTaskMapstruct.toDTO(runtime.task);
    }

    /** 机器人未回 action_result 时的兜底：延迟强制转 PAUSED（与取消的 force finalize 对称） */
    private void schedulePauseGuard(TaskRuntime runtime) {
        worker.schedule(() -> {
            if (activeRuntimes.get(runtime.task.getId()) == runtime
                    && MoveTask.STATUS_PAUSED.equals(runtime.task.getStatus())) {
                return;
            }
            if (activeRuntimes.get(runtime.task.getId()) != runtime || !runtime.pauseRequested) {
                return;
            }
            log.warn("move task pause guard fired: id={}, no action_result, force pause", runtime.task.getId());
            if (runtime.currentSeq > 0) {
                closeSegmentRecord(runtime, runtime.currentSeq + runtime.recordBase,
                        MoveTaskSegment.STATUS_INTERRUPTED, "暂停中断（未收到 action_result，强制暂停）");
            }
            pauseFinalize(runtime);
            publishTaskEvent(runtime.task);
        }, FORCE_FINALIZE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void pauseFinalize(TaskRuntime runtime) {
        ScheduledFuture<?> watchdog = runtime.watchdog;
        if (watchdog != null) {
            watchdog.cancel(false);
        }
        runtime.pauseRequested = false;
        runtime.cancelRequested = false;
        runtime.timeoutRequested = false;
        runtime.currentSeq = 0;
        runtime.currentGoalId = "";
        MoveTask task = runtime.task;
        task.setStatus(MoveTask.STATUS_PAUSED);
        if (task.getErrorMessage() == null || task.getErrorMessage().isBlank()) {
            task.setErrorMessage("已暂停");
        }
        moveTaskRepository.update(task);
        log.info("move task paused: id={}, no={}, robot=({},{})",
                task.getId(), task.getTaskNo(), task.getCurrentX(), task.getCurrentY());
    }

    /**
     * 恢复：从机器人当前位置重规划到原目标（规划器保证接续路线，机器人可能停在段中间），
     * 替换剩余段后继续 follow_edge 闭环；段执行记录编号续在原任务之后。
     */
    public MoveTaskDTO resume(Long id) {
        TaskRuntime runtime = activeRuntimes.get(id);
        if (runtime == null || !MoveTask.STATUS_PAUSED.equals(runtime.task.getStatus())) {
            throw new IllegalStateException("任务 " + id + " 不在暂停状态，无法恢复");
        }
        if (!robotConnection.isConnected()) {
            throw new IllegalStateException("机器人连接未就绪，无法恢复任务");
        }
        var status = telemetry.status();
        if (status == null || !status.poseInitialized()) {
            throw new IllegalStateException("机器人定位未收敛（pose_initialized=false），无法恢复任务");
        }
        if (telemetry.statusFresh() && status.mode() != null
                && !"NAVIGATION".equals(status.mode())) {
            throw new IllegalStateException("机器人 mode=" + status.mode() + "（切图/建图中），无法恢复任务");
        }
        if (!telemetry.armMovementAllowed()) {
            throw new IllegalStateException("机械臂未归位（arm_state=" + telemetry.armState() + "），无法恢复移动");
        }
        RobotPose pose = telemetry.mapPose();
        if (pose == null) {
            throw new IllegalStateException("无法获取机器人当前位置，暂不能恢复");
        }
        MoveTask task = runtime.task;
        List<SegmentPlan> replan = replanSegmentsForResume(task, pose);
        runtime.recordBase += runtime.segments.size();
        runtime.segments = replan;
        runtime.endPointSingle = true;
        task.setSegmentTotal(runtime.recordBase + replan.size());
        String resumeChain = routeChainOf(replan);
        task.setRouteSummary(task.getRouteSummary() + " ⏸续 " + resumeChain);
        task.setStatus(MoveTask.STATUS_DISPATCHED);
        task.setAgvState(null);
        moveTaskRepository.update(task);
        runtime.watchdog = worker.schedule(() -> onWatchdog(runtime),
                tuning.goalTimeoutSeconds(), TimeUnit.SECONDS);
        dispatchSegment(runtime, 1);
        log.info("move task resumed: id={}, no={}, replan segments={}, plan={}",
                task.getId(), task.getTaskNo(), replan.size(), resumeChain);
        publishTaskEvent(task);
        return moveTaskMapstruct.toDTO(task);
    }

    /** 暂停恢复重规划：规划器给接续链（跨路线 legs），失败兜底 GOAL 单段直连原目标 */
    private List<SegmentPlan> replanSegmentsForResume(MoveTask task, RobotPose pose) {
        List<SegmentPlan> chain = new ArrayList<>();
        try {
            var published = mapMetaService.findPublished();
            if (published != null) {
                List<MapWaypoint> waypoints = mapMetaService.listWaypoints(published.getId());
                var robotWp = nearestWaypointWithin(waypoints, pose.x(), pose.y(), WAYPOINT_MATCH_RADIUS * 2);
                var targetWp = nearestWaypointWithin(waypoints, task.getGoalX(), task.getGoalY(),
                        WAYPOINT_MATCH_RADIUS);
                if (robotWp != null && targetWp != null) {
                    var plan = routePlanService.plan(published.getId(),
                            robotWp.getId(), targetWp.getId());
                    if (plan.found() && !plan.steps().isEmpty()) {
                        for (var step : plan.steps()) {
                            if (step.isCrossFloor() || step.pathId() <= 0) {
                                continue;
                            }
                            chain.addAll(buildOrientedLegPlan(step.pathId(),
                                    step.fromWaypointCode(), step.toWaypointCode(), null));
                        }
                        if (!chain.isEmpty()) {
                            return chain;
                        }
                    }
                }
            }
        } catch (Exception exception) {
            log.warn("resume replan failed, fallback GOAL: {}", exception.getMessage());
        }
        return List.of(new SegmentPlan("", "", task.getGoalX(), task.getGoalY(),
                task.getGoalTheta() == null ? 0.0 : task.getGoalTheta(),
                "STRAIGHT", List.of(), null, false, false,
                Double.NaN, Double.NaN, Double.NaN));
    }

    private MapWaypoint nearestWaypointWithin(List<MapWaypoint> waypoints, double x, double y, double radius) {
        MapWaypoint nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (MapWaypoint waypoint : waypoints) {
            double distance = Math.hypot(waypoint.getX() - x, waypoint.getY() - y);
            if (distance < nearestDistance) {
                nearest = waypoint;
                nearestDistance = distance;
            }
        }
        return nearestDistance <= radius ? nearest : null;
    }

    public MoveTaskDTO active() {
        if (!activeRuntimes.isEmpty()) {
            TaskRuntime runtime = activeRuntimes.values().iterator().next();
            return moveTaskMapstruct.toDTO(runtime.task);
        }
        MoveTask stale = moveTaskRepository.findLatestActive();
        return stale == null ? null : moveTaskMapstruct.toDTO(stale);
    }

    public List<MoveTaskDTO> list(String status, Integer limit) {
        int bounded = limit == null ? 50 : Math.clamp(limit, 1, 200);
        return moveTaskMapstruct.toDTOList(moveTaskRepository.findList(status, bounded));
    }

    public MoveTaskDTO get(Long id) {
        return moveTaskMapstruct.toDTO(moveTaskRepository.findById(id));
    }

    public java.util.List<MoveTaskSegment> listSegments(Long taskId) {
        return segmentMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MoveTaskSegment>()
                        .eq(MoveTaskSegment::getTaskId, taskId)
                        .orderByAsc(MoveTaskSegment::getSeq));
    }

    private void insertSegmentRecord(TaskRuntime runtime, int seq, SegmentPlan plan) {
        try {
            MoveTaskSegment record = new MoveTaskSegment();
            record.setTaskId(runtime.task.getId());
            record.setSeq(seq);
            record.setSourceCode(plan.sourceCode());
            record.setTargetCode(plan.targetCode());
            record.setTargetX(plan.x());
            record.setTargetY(plan.y());
            record.setEdgeType(plan.edgeType());
            record.setMaxSpeed(plan.maxSpeed());
            record.setStatus(MoveTaskSegment.STATUS_EXECUTING);
            segmentMapper.insert(record);
        } catch (Exception exception) {
            log.warn("move task {} segment {} record insert failed: {}",
                    runtime.task.getId(), seq, exception.getMessage());
        }
    }

    private void closeSegmentRecord(TaskRuntime runtime, int seq, String status, String message) {
        try {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MoveTaskSegment> update =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MoveTaskSegment>()
                            .eq(MoveTaskSegment::getTaskId, runtime.task.getId())
                            .eq(MoveTaskSegment::getSeq, seq)
                            .eq(MoveTaskSegment::getStatus, MoveTaskSegment.STATUS_EXECUTING)
                            .set(MoveTaskSegment::getStatus, status)
                            .set(MoveTaskSegment::getFinishedAt, java.time.LocalDateTime.now());
            if (message != null && !message.isBlank()) {
                update.set(MoveTaskSegment::getMessage, message);
            }
            segmentMapper.update(null, update);
        } catch (Exception exception) {
            log.warn("move task {} segment {} record close failed: {}",
                    runtime.task.getId(), seq, exception.getMessage());
        }
    }

    private void dispatchSegment(TaskRuntime runtime, int seq) {
        MoveTask task = runtime.task;
        SegmentPlan plan = runtime.segments.get(seq - 1);
        boolean endPoint = seq == runtime.segments.size() && runtime.endPointSingle;
        String commandId = task.getTaskNo() + "-s" + seq;

        FollowEdgeCommand command = new FollowEdgeCommand(
                commandId,
                plan.sourceCode(),
                plan.targetCode(),
                plan.x(), plan.y(), plan.theta(),
                plan.edgeType(),
                plan.maxSpeed() == null ? tuning.defaultMaxSpeed() : plan.maxSpeed(),
                tuning.defaultStep(),
                plan.backUp(),
                plan.reverse(),
                endPoint,
                plan.controlPoints());

        runtime.currentSeq = seq;
        String goalId = robotCommands.dispatchFollowEdge(command, listenerFor(runtime, seq));
        runtime.currentGoalId = goalId;

        insertSegmentRecord(runtime, seq + runtime.recordBase, plan);
        task.markDispatchedGoal(goalId, seq);
        moveTaskRepository.update(task);
    }

    private FollowEdgeListener listenerFor(TaskRuntime runtime, int seq) {
        return new FollowEdgeListener() {
            @Override
            public void onFeedback(FollowFeedback feedback) {
                worker.execute(() -> handleFeedback(runtime, seq, feedback));
            }

            @Override
            public void onResult(int status, boolean success, String message, String commandId) {
                worker.execute(() -> handleResult(runtime, seq, status, success, message));
            }
        };
    }

    private void handleFeedback(TaskRuntime runtime, int seq, FollowEdgeListener.FollowFeedback feedback) {
        if (activeRuntimes.get(runtime.task.getId()) != runtime || runtime.currentSeq != seq) {
            return;
        }
        MoveTask task = runtime.task;
        if (feedback.x() != null) {
            task.setCurrentX(feedback.x());
        }
        if (feedback.y() != null) {
            task.setCurrentY(feedback.y());
        }
        if (feedback.theta() != null) {
            task.setCurrentTheta(feedback.theta());
        }
        if (feedback.state() != null) {
            task.setAgvState(feedback.state());
        }

        boolean statusChanged = task.markExecutingIfDispatched();
        long now = System.currentTimeMillis();
        if (statusChanged || now - runtime.lastFeedbackWriteMs >= FEEDBACK_DB_WRITE_INTERVAL_MS) {
            runtime.lastFeedbackWriteMs = now;
            // 条件更新（仅非终态时刷新）：防迟到的 feedback 事务把已终结的 FAILED 覆盖回 DISPATCHED（曾致 busy 锁死）
            moveTaskRepository.updateFeedback(task, statusChanged);
            publishTaskEvent(task);
        }
    }

    private void handleResult(TaskRuntime runtime, int seq, int goalStatus, boolean success, String message) {
        if (activeRuntimes.get(runtime.task.getId()) != runtime || runtime.currentSeq != seq) {
            return;
        }
        MoveTask task = runtime.task;
        String terminal = terminalStatus(goalStatus, runtime.cancelRequested, runtime.timeoutRequested,
                runtime.pauseRequested);
        if (MoveTask.STATUS_PAUSED.equals(terminal) && seq < runtime.segments.size()) {
            // 暂停请求：无论本段成败都停在当前段边界（含"刚好走完本段"的竞态），剩余段作废待恢复重规划
            closeSegmentRecord(runtime, seq + runtime.recordBase, MoveTaskSegment.STATUS_INTERRUPTED,
                    "暂停中断（剩余段待恢复后重规划）");
            pauseFinalize(runtime);
            publishTaskEvent(task);
            return;
        }
        if (MoveTask.STATUS_SUCCEEDED.equals(terminal) && seq < runtime.segments.size()) {
            closeSegmentRecord(runtime, seq + runtime.recordBase, MoveTaskSegment.STATUS_SUCCEEDED, message);
            dispatchSegment(runtime, seq + 1);
            publishTaskEvent(task);
            return;
        }
        String segmentStatus = MoveTask.STATUS_SUCCEEDED.equals(terminal)
                ? MoveTaskSegment.STATUS_SUCCEEDED : terminal;
        closeSegmentRecord(runtime, seq + runtime.recordBase, segmentStatus,
                MoveTask.STATUS_SUCCEEDED.equals(terminal) ? message
                        : terminalMessage(terminal, goalStatus, message));
        if (MoveTask.STATUS_PAUSED.equals(terminal)) {
            // 暂停请求打在最后一段上：任务实际已到终点，按暂停收口（恢复时重规划会发现已在目标点）
            pauseFinalize(runtime);
        } else {
            finalizeTask(runtime, terminal, terminalMessage(terminal, goalStatus, message));
        }
        publishTaskEvent(task);
    }

    private void onWatchdog(TaskRuntime runtime) {
        if (activeRuntimes.get(runtime.task.getId()) != runtime) {
            return;
        }
        log.warn("move task watchdog fired: id={}, timeout={}s, cancelling",
                runtime.task.getId(), tuning.goalTimeoutSeconds());
        runtime.timeoutRequested = true;
        try {
            robotCommands.cancelGoal(runtime.currentGoalId);
        } catch (Exception e) {
            log.warn("watchdog cancel failed: {}", e.getMessage());
        }
        scheduleForceFinalize(runtime, MoveTask.STATUS_TIMEOUT,
                "任务超时（" + tuning.goalTimeoutSeconds() + "s）且未收到 action_result，已强制结束");
    }

    private void scheduleForceFinalize(TaskRuntime runtime, String status, String reason) {
        worker.schedule(() -> {
            if (activeRuntimes.remove(runtime.task.getId(), runtime)) {
                finalizeTask(runtime, status, reason);
                log.warn("move task force finalized: id={}, status={}", runtime.task.getId(), status);
            }
        }, FORCE_FINALIZE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void finalizeTask(TaskRuntime runtime, String status, String message) {
        activeRuntimes.remove(runtime.task.getId(), runtime);
        ScheduledFuture<?> watchdog = runtime.watchdog;
        if (watchdog != null) {
            watchdog.cancel(false);
        }
        if (runtime.currentSeq > 0) {
            closeSegmentRecord(runtime, runtime.currentSeq + runtime.recordBase,
                    MoveTask.STATUS_SUCCEEDED.equals(status) ? MoveTaskSegment.STATUS_SUCCEEDED : status, message);
        }
        MoveTask task = runtime.task;
        task.markFinished(status, message);
        moveTaskRepository.update(task);
        log.info("move task finished: id={}, no={}, status={}, message={}",
                task.getId(), task.getTaskNo(), status, message);
        publishTaskEvent(task);
    }

    private void publishTaskEvent(MoveTask task) {
        events.publish("task", moveTaskMapstruct.toDTO(task));
    }

    /** 由段链拼点位名路线（相邻去重，跳过无名段），如 "H9→P13→P2" */
    static String routeChainOf(List<SegmentPlan> segments) {
        List<String> codes = new ArrayList<>(segments.size() + 1);
        for (SegmentPlan segment : segments) {
            if (segment.sourceCode() != null && !segment.sourceCode().isBlank() && codes.isEmpty()) {
                codes.add(segment.sourceCode());
            }
            if (segment.targetCode() != null && !segment.targetCode().isBlank()
                    && (codes.isEmpty() || !codes.get(codes.size() - 1).equals(segment.targetCode()))) {
                codes.add(segment.targetCode());
            }
        }
        return codes.isEmpty() ? "" : String.join("→", codes);
    }

    private List<SegmentPlan> buildPathPlan(Long pathId, Double maxSpeedOverride) {
        List<SegmentPlan> v2Plan = buildV2PathPlan(pathId, maxSpeedOverride);
        if (!v2Plan.isEmpty()) {
            log.info("FOLLOW_PATH 使用 V2 路线: pathId={}, segments={}", pathId, v2Plan.size());
        }
        return v2Plan;
    }

    private void requireDeployedPath(Long pathId) {
        MapPath v2Path = mapMetaService.getPath(pathId);
        if (v2Path == null) {
            throw new BusinessException(TaskErrorCode.PATH_NOT_FOUND, "路线 " + pathId + " 不存在（V2 路线）");
        }
        if (!MapPath.STATUS_DEPLOYED.equals(v2Path.getStatus())) {
            throw new IllegalStateException("路线 " + pathId + " 未部署（status=" + v2Path.getStatus() + "）");
        }
    }

    /**
     * 按规划给定进出口定向一条路线的段：
     * 与路线定义同向 → 原段；反向 → 整线倒序；环线/中途接入 → 截取 fromCode→toCode 子链。
     */
    private List<SegmentPlan> buildOrientedLegPlan(Long pathId, String fromCode, String toCode, Double maxSpeedOverride) {
        List<SegmentPlan> raw = buildPathPlan(pathId, maxSpeedOverride);
        if (raw.isEmpty() || fromCode == null || toCode == null) {
            return raw;
        }
        SegmentPlan first = raw.get(0);
        SegmentPlan last = raw.get(raw.size() - 1);
        if (fromCode.equals(first.sourceCode()) && toCode.equals(last.targetCode())) {
            return raw;
        }
        if (fromCode.equals(last.targetCode()) && toCode.equals(first.sourceCode())) {
            return reversePathPlan(raw);
        }
        for (int i = 0; i < raw.size(); i++) {
            if (!fromCode.equals(raw.get(i).sourceCode())) {
                continue;
            }
            List<SegmentPlan> sub = new java.util.ArrayList<>();
            for (int j = i; j < raw.size(); j++) {
                sub.add(raw.get(j));
                if (toCode.equals(raw.get(j).targetCode())) {
                    log.info("FOLLOW_PATH pathId={} 环线/中途接入: 取段 {}..{}（{}→{}）",
                            pathId, i + 1, j + 1, fromCode, toCode);
                    return sub;
                }
            }
        }
        List<SegmentPlan> reversed = reversePathPlan(raw);
        for (int i = 0; i < reversed.size(); i++) {
            if (!fromCode.equals(reversed.get(i).sourceCode())) {
                continue;
            }
            List<SegmentPlan> sub = new java.util.ArrayList<>();
            for (int j = i; j < reversed.size(); j++) {
                sub.add(reversed.get(j));
                if (toCode.equals(reversed.get(j).targetCode())) {
                    log.info("FOLLOW_PATH pathId={} 环线/中途接入(反向): 取段 {}..{}（{}→{}）",
                            pathId, i + 1, j + 1, fromCode, toCode);
                    return sub;
                }
            }
        }
        throw new IllegalStateException("路线 " + pathId + " 段链与规划进出口不匹配（" + fromCode + "→" + toCode + "）");
    }

    private List<SegmentPlan> buildV2PathPlan(Long pathId, Double maxSpeedOverride) {
        MapPath v2Path = mapMetaService.getPath(pathId);
        if (v2Path == null) {
            return List.of();
        }
        List<MapPathSegment> segments = mapMetaService.listPathSegments(pathId);
        if (segments.isEmpty()) {
            return List.of();
        }
        List<Long> waypointIds = new ArrayList<>();
        for (var segment : segments) {
            waypointIds.add(segment.getSourceWaypointId());
            waypointIds.add(segment.getTargetWaypointId());
        }
        Map<Long, MapWaypoint> waypoints =
                mapMetaService.listWaypointsByIds(waypointIds.stream().distinct().toList())
                        .stream()
                        .collect(Collectors.toMap(MapWaypoint::getId, Function.identity()));

        List<SegmentPlan> plans = new ArrayList<>(segments.size());
        for (var segment : segments) {
            var source = waypoints.get(segment.getSourceWaypointId());
            var target = waypoints.get(segment.getTargetWaypointId());
            if (source == null || target == null) {
                throw new IllegalStateException("V2 路线段引用的点位不存在，数据不一致: pathId=" + pathId);
            }
            Double maxSpeed = maxSpeedOverride != null ? maxSpeedOverride
                    : segment.getMaxSpeed() != null ? segment.getMaxSpeed()
                    : v2Path.getMaxSpeed();
            List<FollowControlPoint> controlPoints = parseV2ControlPoints(segment.getControlPoints());
            plans.add(new SegmentPlan(source.getWaypointCode(), target.getWaypointCode(),
                    target.getX(), target.getY(), target.getYaw(),
                    segment.getSegmentType(), controlPoints,
                    maxSpeed,
                    Boolean.TRUE.equals(segment.getBackUp()),
                    Boolean.TRUE.equals(segment.getReverse()),
                    source.getX(), source.getY(), source.getYaw()));
        }
        return plans;
    }

    private static List<FollowControlPoint> parseV2ControlPoints(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = com.agv.navdeployer.map.application.MetadataJson.MAPPER.readTree(json);
            List<FollowControlPoint> points = new ArrayList<>();
            for (JsonNode node : array) {
                if (node.isArray() && node.size() >= 2) {
                    points.add(new FollowControlPoint(node.get(0).asDouble(), node.get(1).asDouble()));
                }
            }
            return points;
        } catch (Exception e) {
            log.warn("V2 控制点解析失败（回退空）: {}", e.getMessage());
            return List.of();
        }
    }

    static List<SegmentPlan> withStartHopIfNeeded(List<SegmentPlan> segments,
                                                  Double robotX, Double robotY, double radiusMeters) {
        if (robotX == null || robotY == null || segments.isEmpty()) {
            return segments;
        }
        SegmentPlan first = segments.get(0);
        if (Double.isNaN(first.sx()) || Double.isNaN(first.sy())) {
            return segments;
        }
        double distance = Math.hypot(robotX - first.sx(), robotY - first.sy());
        if (distance <= radiusMeters) {
            return segments;
        }
        double heading = Math.atan2(first.y() - first.sy(), first.x() - first.sx());
        SegmentPlan hop = new SegmentPlan("", first.sourceCode(), first.sx(), first.sy(), heading,
                "STRAIGHT", List.of(), first.maxSpeed(), false, false,
                Double.NaN, Double.NaN, Double.NaN);
        List<SegmentPlan> withHop = new ArrayList<>(segments.size() + 1);
        withHop.add(hop);
        withHop.addAll(segments);
        return withHop;
    }

    // 倒序等价变换：贝塞尔控制点须同步反转；reverse 置 false 防机器人侧对已反转段二次反转
    static List<SegmentPlan> reversePathPlan(List<SegmentPlan> segments) {
        List<SegmentPlan> out = new ArrayList<>(segments.size());
        for (int i = segments.size() - 1; i >= 0; i--) {
            SegmentPlan s = segments.get(i);
            List<FollowControlPoint> cps = new ArrayList<>(s.controlPoints());
            java.util.Collections.reverse(cps);
            out.add(new SegmentPlan(s.targetCode(), s.sourceCode(),
                    s.sx(), s.sy(), s.syaw(),
                    s.edgeType(), cps,
                    s.maxSpeed(),
                    s.backUp(),
                    false,
                    Double.NaN, Double.NaN, Double.NaN));
        }
        return out;
    }

    static boolean shouldReverseForNearestEnd(List<SegmentPlan> segments, Double robotX, Double robotY) {
        if (robotX == null || robotY == null || segments.isEmpty()) {
            return false;
        }
        SegmentPlan first = segments.get(0);
        SegmentPlan last = segments.get(segments.size() - 1);
        if (Double.isNaN(first.sx()) || Double.isNaN(last.x())) {
            return false;
        }
        double distStart = Math.hypot(robotX - first.sx(), robotY - first.sy());
        double distEnd = Math.hypot(robotX - last.x(), robotY - last.y());
        return distEnd < distStart;
    }

    static String terminalStatus(int goalStatus, boolean cancelRequested, boolean timeoutRequested,
                                 boolean pauseRequested) {
        return switch (goalStatus) {
            case 4 -> MoveTask.STATUS_SUCCEEDED;
            case 5 -> timeoutRequested ? MoveTask.STATUS_TIMEOUT
                    : pauseRequested ? MoveTask.STATUS_PAUSED : MoveTask.STATUS_CANCELLED;
            default -> pauseRequested ? MoveTask.STATUS_PAUSED : MoveTask.STATUS_FAILED;
        };
    }

    private static String terminalMessage(String terminal, int goalStatus, String message) {
        if (MoveTask.STATUS_SUCCEEDED.equals(terminal)) {
            return StringUtils.hasText(message) ? message : null;
        }
        String prefix = switch (terminal) {
            case MoveTask.STATUS_CANCELLED -> "任务已取消";
            case MoveTask.STATUS_TIMEOUT -> "任务超时";
            default -> "执行失败（action status=" + goalStatus + "）";
        };
        return StringUtils.hasText(message) ? prefix + ": " + message : prefix;
    }

    record SegmentPlan(
            String sourceCode,
            String targetCode,
            double x,
            double y,
            double theta,
            String edgeType,
            List<FollowControlPoint> controlPoints,
            Double maxSpeed,
            boolean backUp,
            boolean reverse,
            double sx,
            double sy,
            double syaw
    ) {
    }

    private static final class TaskRuntime {
        final MoveTask task;
        List<SegmentPlan> segments;
        boolean endPointSingle;
        volatile int currentSeq;
        volatile String currentGoalId = "";
        volatile boolean cancelRequested;
        volatile boolean timeoutRequested;
        volatile boolean pauseRequested;
        volatile long lastFeedbackWriteMs;
        volatile ScheduledFuture<?> watchdog;
        int recordBase;

        TaskRuntime(MoveTask task, List<SegmentPlan> segments, boolean endPointSingle) {
            this.task = task;
            this.segments = segments;
            this.endPointSingle = endPointSingle;
        }
    }
}
