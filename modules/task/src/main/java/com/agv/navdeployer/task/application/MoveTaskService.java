package com.agv.navdeployer.task.application;

import com.agv.navdeployer.common.error.BusinessException;
import com.agv.navdeployer.task.application.cmd.MoveTaskCreateCmd;
import com.agv.navdeployer.task.application.dto.MoveTaskDTO;
import com.agv.navdeployer.task.domain.movetask.MoveTask;
import com.agv.navdeployer.task.domain.movetask.TaskErrorCode;
import com.agv.navdeployer.task.domain.port.RobotConnectionPort;
import com.agv.navdeployer.task.domain.repository.MoveTaskRepository;
import com.agv.navdeployer.task.infrastructure.mapstruct.MoveTaskMapstruct;
import com.agv.navdeployer.robot.infrastructure.RosCommandDispatcher;
import com.agv.navdeployer.robot.infrastructure.RosCommandDispatcher.ControlPoint;
import com.agv.navdeployer.robot.infrastructure.RosCommandDispatcher.FollowEdgeGoal;
import com.agv.navdeployer.robot.domain.SimAgvProperties;
import com.agv.navdeployer.robot.interfaces.SimAgvSsePublisher;
import com.agv.navdeployer.robot.domain.SimAgvTelemetry;
import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

    private final MoveTaskRepository moveTaskRepository;
    private final MoveTaskMapstruct moveTaskMapstruct;
    private final RobotConnectionPort robotConnection;
    private final RosCommandDispatcher dispatcher;
    private final SimAgvTelemetry telemetry;
    private final SimAgvProperties props;
    private final SimAgvSsePublisher ssePublisher;
    private final com.agv.navdeployer.robot.domain.ArmStateMonitor armStateMonitor;
    private final com.agv.navdeployer.map.infrastructure.repository.mapper.MapPathMapper v2PathMapper;
    private final com.agv.navdeployer.map.infrastructure.repository.mapper.MapPathSegmentMapper v2SegmentMapper;
    private final com.agv.navdeployer.map.infrastructure.repository.mapper.MapWaypointMapper v2WaypointMapper;
    private final com.agv.navdeployer.map.infrastructure.repository.mapper.MapMetaMapper v2MapMapper;
    private final RobotOperationalService operationalService;
    private final MapAlignmentGuard mapAlignmentGuard;

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
                           RosCommandDispatcher dispatcher,
                           SimAgvTelemetry telemetry,
                           SimAgvProperties props,
                           SimAgvSsePublisher ssePublisher,
                           com.agv.navdeployer.robot.domain.ArmStateMonitor armStateMonitor,
                           com.agv.navdeployer.map.infrastructure.repository.mapper.MapPathMapper v2PathMapper,
                           com.agv.navdeployer.map.infrastructure.repository.mapper.MapPathSegmentMapper v2SegmentMapper,
                           com.agv.navdeployer.map.infrastructure.repository.mapper.MapWaypointMapper v2WaypointMapper,
                           com.agv.navdeployer.map.infrastructure.repository.mapper.MapMetaMapper v2MapMapper,
                           RobotOperationalService operationalService,
                           MapAlignmentGuard mapAlignmentGuard) {
        this.moveTaskRepository = moveTaskRepository;
        this.moveTaskMapstruct = moveTaskMapstruct;
        this.robotConnection = robotConnection;
        this.dispatcher = dispatcher;
        this.telemetry = telemetry;
        this.props = props;
        this.ssePublisher = ssePublisher;
        this.armStateMonitor = armStateMonitor;
        this.v2PathMapper = v2PathMapper;
        this.v2SegmentMapper = v2SegmentMapper;
        this.v2WaypointMapper = v2WaypointMapper;
        this.v2MapMapper = v2MapMapper;
        this.operationalService = operationalService;
        this.mapAlignmentGuard = mapAlignmentGuard;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleTasks() {
        int recovered = moveTaskRepository.markStaleInterrupted("服务重启，任务中断");
        if (recovered > 0) {
            log.warn("recovered {} stale move tasks to FAILED on startup", recovered);
        }
    }

    public MoveTaskDTO create(MoveTaskCreateCmd dto) {
        return create(dto, "manual");
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
            throw new IllegalStateException("rosbridge 未连接，无法下发移动任务");
        }
        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        if (status == null || !status.poseInitialized()) {
            throw new IllegalStateException("机器人定位未收敛（pose_initialized=false），禁止下发导航指令");
        }
        if (telemetry.isStatusFresh(props.getStatusFreshMs()) && status.mode() != null
                && !"NAVIGATION".equals(status.mode())) {
            throw new IllegalStateException("机器人 mode=" + status.mode()
                    + "（切图/建图中），禁止下发导航指令，请等待回到 NAVIGATION");
        }
        if (armStateMonitor != null && !armStateMonitor.movementAllowed()) {
            throw new IllegalStateException("机械臂未归位（arm_state=" + armStateMonitor.state()
                    + "），禁止移动作业，请先收臂归位");
        }
        if (mapAlignmentGuard != null) {
            mapAlignmentGuard.requireAligned();
        }

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
                com.agv.navdeployer.map.domain.MapWaypoint waypoint = v2WaypointMapper.selectById(dto.waypointId());
                if (waypoint == null) {
                    throw new BusinessException(TaskErrorCode.WAYPOINT_NOT_FOUND, "点位 " + dto.waypointId() + " 不存在");
                }
                com.agv.navdeployer.map.domain.MapMeta published = v2MapMapper.selectOne(
                        Wrappers.lambdaQuery(com.agv.navdeployer.map.domain.MapMeta.class)
                                .eq(com.agv.navdeployer.map.domain.MapMeta::getStatus,
                                        com.agv.navdeployer.map.domain.MapMeta.STATUS_PUBLISHED)
                                .last("LIMIT 1"));
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
                if (dto.pathId() == null) {
                    throw new IllegalArgumentException("FOLLOW_PATH 任务必须携带 path_id");
                }
                com.agv.navdeployer.map.domain.MapPath v2Path = v2PathMapper.selectById(dto.pathId());
                if (v2Path == null) {
                    throw new BusinessException(TaskErrorCode.PATH_NOT_FOUND, "路线 " + dto.pathId() + " 不存在（V2 路线）");
                }
                if (!com.agv.navdeployer.map.domain.MapPath.STATUS_DEPLOYED.equals(v2Path.getStatus())) {
                    throw new IllegalStateException("路线 " + dto.pathId() + " 未部署（status=" + v2Path.getStatus() + "）");
                }
                segments = buildPathPlan(dto.pathId(), dto.maxSpeed());
                if (segments.isEmpty()) {
                    throw new IllegalStateException("路线 " + dto.pathId() + " 没有可执行的边");
                }
                SimAgvTelemetry.PoseSnapshot pose = telemetry.getMapPose();
                if (pose != null && shouldReverseForNearestEnd(segments, pose.x(), pose.y())) {
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
                        props.getFollowPathStartRadius());
                if (segments.size() > edgeCount && pose != null) {
                    SegmentPlan firstEdge = segments.get(1);
                    log.info("follow path {} start hop prepended: robot=({},{}) start=({},{}), distance={}m > {}m",
                            dto.pathId(), pose.x(), pose.y(), firstEdge.sx(), firstEdge.sy(),
                            String.format("%.2f", Math.hypot(pose.x() - firstEdge.sx(), pose.y() - firstEdge.sy())),
                            props.getFollowPathStartRadius());
                }
                SegmentPlan last = segments.get(segments.size() - 1);
                task.setPathId(dto.pathId());
                task.setGoalX(last.x());
                task.setGoalY(last.y());
                task.setGoalTheta(last.theta());
                endPointSingle = true;
            }
            default -> throw new IllegalArgumentException("不支持的 task_type: " + dto.taskType());
        }

        task.setSegmentTotal(segments.size());
        moveTaskRepository.insert(task);

        TaskRuntime runtime = new TaskRuntime(task, segments, endPointSingle);
        activeRuntimes.put(task.getId(), runtime);
        runtime.watchdog = worker.schedule(() -> onWatchdog(runtime),
                props.getGoalTimeoutSeconds(), TimeUnit.SECONDS);
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
        runtime.cancelRequested = true;
        dispatcher.cancelGoal(runtime.currentGoalId);
        scheduleForceFinalize(runtime, MoveTask.STATUS_CANCELLED, "已下发取消，但未收到 action_result，强制终结");
        log.info("move task cancel requested: id={}, goal={}", id, runtime.currentGoalId);
        return moveTaskMapstruct.toDTO(runtime.task);
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

    private void dispatchSegment(TaskRuntime runtime, int seq) {
        MoveTask task = runtime.task;
        SegmentPlan plan = runtime.segments.get(seq - 1);
        boolean endPoint = seq == runtime.segments.size() && runtime.endPointSingle;
        String commandId = task.getTaskNo() + "-s" + seq;

        FollowEdgeGoal goal = new FollowEdgeGoal(
                commandId,
                plan.targetCode(),
                plan.x(), plan.y(), plan.theta(),
                "edge-" + commandId,
                plan.edgeType(),
                plan.sourceCode(),
                plan.targetCode(),
                plan.maxSpeed() == null ? props.getDefaultMaxSpeed() : plan.maxSpeed(),
                plan.backUp(),
                plan.reverse(),
                props.getDefaultStep(),
                endPoint,
                plan.controlPoints());

        runtime.currentSeq = seq;
        String goalId = dispatcher.dispatchFollowEdge(goal, listenerFor(runtime, seq));
        runtime.currentGoalId = goalId;

        task.markDispatchedGoal(goalId, seq);
        moveTaskRepository.update(task);
    }

    private RosCommandDispatcher.ActionListener listenerFor(TaskRuntime runtime, int seq) {
        return new RosCommandDispatcher.ActionListener() {
            @Override
            public void onFeedback(JsonNode values) {
                worker.execute(() -> handleFeedback(runtime, seq, values));
            }

            @Override
            public void onResult(int status, boolean success, String message, String commandId) {
                worker.execute(() -> handleResult(runtime, seq, status, success, message));
            }
        };
    }

    private void handleFeedback(TaskRuntime runtime, int seq, JsonNode values) {
        if (activeRuntimes.get(runtime.task.getId()) != runtime || runtime.currentSeq != seq) {
            return;
        }
        MoveTask task = runtime.task;
        task.setCurrentX(values.path("x").asDouble(task.getCurrentX() == null ? 0 : task.getCurrentX()));
        task.setCurrentY(values.path("y").asDouble(task.getCurrentY() == null ? 0 : task.getCurrentY()));
        task.setCurrentTheta(values.path("theta").asDouble(task.getCurrentTheta() == null ? 0 : task.getCurrentTheta()));
        task.setAgvState(values.path("state").asText(task.getAgvState()));

        boolean statusChanged = task.markExecutingIfDispatched();
        long now = System.currentTimeMillis();
        if (statusChanged || now - runtime.lastFeedbackWriteMs >= FEEDBACK_DB_WRITE_INTERVAL_MS) {
            runtime.lastFeedbackWriteMs = now;
            moveTaskRepository.update(task);
            publishTaskEvent(task);
        }
    }

    private void handleResult(TaskRuntime runtime, int seq, int goalStatus, boolean success, String message) {
        if (activeRuntimes.get(runtime.task.getId()) != runtime || runtime.currentSeq != seq) {
            return;
        }
        MoveTask task = runtime.task;
        String terminal = terminalStatus(goalStatus, runtime.cancelRequested, runtime.timeoutRequested);
        if (MoveTask.STATUS_SUCCEEDED.equals(terminal) && seq < runtime.segments.size()) {
            dispatchSegment(runtime, seq + 1);
            publishTaskEvent(task);
            return;
        }
        finalizeTask(runtime, terminal, terminalMessage(terminal, goalStatus, message));
    }

    private void onWatchdog(TaskRuntime runtime) {
        if (activeRuntimes.get(runtime.task.getId()) != runtime) {
            return;
        }
        log.warn("move task watchdog fired: id={}, timeout={}s, cancelling",
                runtime.task.getId(), props.getGoalTimeoutSeconds());
        runtime.timeoutRequested = true;
        try {
            dispatcher.cancelGoal(runtime.currentGoalId);
        } catch (Exception e) {
            log.warn("watchdog cancel failed: {}", e.getMessage());
        }
        scheduleForceFinalize(runtime, MoveTask.STATUS_TIMEOUT,
                "任务超时（" + props.getGoalTimeoutSeconds() + "s）且未收到 action_result，已强制结束");
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
        MoveTask task = runtime.task;
        task.markFinished(status, message);
        moveTaskRepository.update(task);
        log.info("move task finished: id={}, no={}, status={}, message={}",
                task.getId(), task.getTaskNo(), status, message);
        publishTaskEvent(task);
    }

    private void publishTaskEvent(MoveTask task) {
        ssePublisher.publishEvent("task", moveTaskMapstruct.toDTO(task));
    }

    private List<SegmentPlan> buildPathPlan(Long pathId, Double maxSpeedOverride) {
        List<SegmentPlan> v2Plan = buildV2PathPlan(pathId, maxSpeedOverride);
        if (!v2Plan.isEmpty()) {
            log.info("FOLLOW_PATH 使用 V2 路线: pathId={}, segments={}", pathId, v2Plan.size());
        }
        return v2Plan;
    }

    private List<SegmentPlan> buildV2PathPlan(Long pathId, Double maxSpeedOverride) {
        com.agv.navdeployer.map.domain.MapPath v2Path = v2PathMapper.selectById(pathId);
        if (v2Path == null) {
            return List.of();
        }
        List<com.agv.navdeployer.map.domain.MapPathSegment> segments = v2SegmentMapper.selectList(
                Wrappers.lambdaQuery(com.agv.navdeployer.map.domain.MapPathSegment.class)
                        .eq(com.agv.navdeployer.map.domain.MapPathSegment::getPathId, pathId)
                        .orderByAsc(com.agv.navdeployer.map.domain.MapPathSegment::getSeq));
        if (segments.isEmpty()) {
            return List.of();
        }
        List<Long> waypointIds = new ArrayList<>();
        for (var segment : segments) {
            waypointIds.add(segment.getSourceWaypointId());
            waypointIds.add(segment.getTargetWaypointId());
        }
        Map<Long, com.agv.navdeployer.map.domain.MapWaypoint> waypoints =
                v2WaypointMapper.selectBatchIds(waypointIds.stream().distinct().toList())
                        .stream()
                        .collect(Collectors.toMap(
                                com.agv.navdeployer.map.domain.MapWaypoint::getId,
                                Function.identity()));

        List<SegmentPlan> plans = new ArrayList<>(segments.size());
        for (var segment : segments) {
            var source = waypoints.get(segment.getSourceWaypointId());
            var target = waypoints.get(segment.getTargetWaypointId());
            if (source == null || target == null) {
                throw new IllegalStateException("V2 路线段引用的点位不存在，数据不一致: pathId=" + pathId);
            }
            Double maxSpeed = maxSpeedOverride != null ? maxSpeedOverride : segment.getMaxSpeed();
            List<ControlPoint> controlPoints = parseV2ControlPoints(segment.getControlPoints());
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

    private static List<ControlPoint> parseV2ControlPoints(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = com.agv.navdeployer.map.application.MetadataJson.MAPPER.readTree(json);
            List<ControlPoint> points = new ArrayList<>();
            for (JsonNode node : array) {
                if (node.isArray() && node.size() >= 2) {
                    points.add(new ControlPoint(node.get(0).asDouble(), node.get(1).asDouble()));
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
            List<ControlPoint> cps = new ArrayList<>(s.controlPoints());
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

    static String terminalStatus(int goalStatus, boolean cancelRequested, boolean timeoutRequested) {
        return switch (goalStatus) {
            case 4 -> MoveTask.STATUS_SUCCEEDED;
            case 5 -> timeoutRequested ? MoveTask.STATUS_TIMEOUT : MoveTask.STATUS_CANCELLED;
            default -> MoveTask.STATUS_FAILED;
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
            List<ControlPoint> controlPoints,
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
        final List<SegmentPlan> segments;
        final boolean endPointSingle;
        volatile int currentSeq;
        volatile String currentGoalId = "";
        volatile boolean cancelRequested;
        volatile boolean timeoutRequested;
        volatile long lastFeedbackWriteMs;
        volatile ScheduledFuture<?> watchdog;

        TaskRuntime(MoveTask task, List<SegmentPlan> segments, boolean endPointSingle) {
            this.task = task;
            this.segments = segments;
            this.endPointSingle = endPointSingle;
        }
    }
}
