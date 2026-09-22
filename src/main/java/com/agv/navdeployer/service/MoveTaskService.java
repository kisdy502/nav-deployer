package com.agv.navdeployer.service;

import com.agv.navdeployer.dto.MoveTaskCreateDTO;
import com.agv.navdeployer.entity.MoveTask;
import com.agv.navdeployer.entity.NavPath;
import com.agv.navdeployer.entity.NavPoint;
import com.agv.navdeployer.exception.NotFoundException;
import com.agv.navdeployer.mapper.MoveTaskMapper;
import com.agv.navdeployer.mapper.NavPointMapper;
import com.agv.navdeployer.sim.RosCommandDispatcher;
import com.agv.navdeployer.sim.RosCommandDispatcher.ControlPoint;
import com.agv.navdeployer.sim.RosCommandDispatcher.FollowEdgeGoal;
import com.agv.navdeployer.sim.RosbridgeClient;
import com.agv.navdeployer.sim.SimAgvProperties;
import com.agv.navdeployer.sim.SimAgvSsePublisher;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import com.agv.navdeployer.vo.MoveTaskVO;
import com.agv.navdeployer.vo.PathEdgeVO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import java.util.stream.Stream;

/**
 * 移动任务：创建即下发 /agv/follow_edge（TO_POINT / GOAL 单段，FOLLOW_PATH 逐段串行），
 * 以 goal id 关联 feedback / result 做状态机闭环；看门狗超时自动取消并强制终结。
 *
 * 回调（WS IO 线程）只投递到单线程 worker 串行处理，天然避免任务状态竞争；
 * 创建路径不套大事务：insert 即提交，防止 result 回调先于事务可见。
 */
@Service
public class MoveTaskService {

    private static final Logger log = LoggerFactory.getLogger(MoveTaskService.class);
    private static final long FEEDBACK_DB_WRITE_INTERVAL_MS = 1000L;
    private static final long FORCE_FINALIZE_DELAY_MS = 15000L;

    private final MoveTaskMapper moveTaskMapper;
    private final NavPointMapper navPointMapper;
    private final NavPathService navPathService;
    private final NavMapService navMapService;
    private final RosCommandDispatcher dispatcher;
    private final RosbridgeClient rosbridgeClient;
    private final SimAgvTelemetry telemetry;
    private final SimAgvProperties props;
    private final SimAgvSsePublisher ssePublisher;

    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "move-task-worker");
                thread.setDaemon(true);
                return thread;
            });
    /** 在途任务运行时（taskId → runtime），worker 线程与创建线程并发访问。 */
    private final Map<Long, TaskRuntime> activeRuntimes = new ConcurrentHashMap<>();

    public MoveTaskService(MoveTaskMapper moveTaskMapper,
                           NavPointMapper navPointMapper,
                           NavPathService navPathService,
                           NavMapService navMapService,
                           RosCommandDispatcher dispatcher,
                           RosbridgeClient rosbridgeClient,
                           SimAgvTelemetry telemetry,
                           SimAgvProperties props,
                           SimAgvSsePublisher ssePublisher) {
        this.moveTaskMapper = moveTaskMapper;
        this.navPointMapper = navPointMapper;
        this.navPathService = navPathService;
        this.navMapService = navMapService;
        this.dispatcher = dispatcher;
        this.rosbridgeClient = rosbridgeClient;
        this.telemetry = telemetry;
        this.props = props;
        this.ssePublisher = ssePublisher;
    }

    /** 服务重启时把遗留的非终态任务标记为失败（goal 关联已丢失，无法再收到 result）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleTasks() {
        LambdaUpdateWrapper<MoveTask> wrapper = Wrappers.lambdaUpdate(MoveTask.class)
                .in(MoveTask::getStatus, MoveTask.STATUS_CREATED, MoveTask.STATUS_DISPATCHED,
                        MoveTask.STATUS_EXECUTING)
                .set(MoveTask::getStatus, MoveTask.STATUS_FAILED)
                .set(MoveTask::getErrorMessage, "服务重启，任务中断")
                .set(MoveTask::getFinishedAt, java.time.LocalDateTime.now());
        int recovered = moveTaskMapper.update(null, wrapper);
        if (recovered > 0) {
            log.warn("recovered {} stale move tasks to FAILED on startup", recovered);
        }
    }

    /** 创建并下发移动任务。 */
    public MoveTaskVO create(MoveTaskCreateDTO dto) {
        if (!activeRuntimes.isEmpty()) {
            throw new IllegalStateException("已有在途任务（" + activeRuntimes.size() + " 条），机器人同一时刻只允许一个 goal，请先取消或等待完成");
        }
        if (!rosbridgeClient.isConnected()) {
            throw new IllegalStateException("rosbridge 未连接，无法下发移动任务");
        }
        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        if (status == null || !status.poseInitialized()) {
            throw new IllegalStateException("机器人定位未收敛（pose_initialized=false），禁止下发导航指令");
        }
        // 模式闸门：切图（RELOCALIZING）/建图（MAPPING）期间机器人会拒绝导航，这里提前拦下并给出原因
        if (telemetry.isStatusFresh(props.getStatusFreshMs()) && status.mode() != null
                && !"NAVIGATION".equals(status.mode())) {
            throw new IllegalStateException("机器人 mode=" + status.mode()
                    + "（切图/建图中），禁止下发导航指令，请等待回到 NAVIGATION");
        }

        MoveTask task = new MoveTask();
        task.setTaskNo("move-" + UUID.randomUUID());
        task.setTaskType(dto.taskType());
        task.setStatus(MoveTask.STATUS_CREATED);
        List<SegmentPlan> segments;
        boolean endPointSingle;

        switch (dto.taskType()) {
            case MoveTask.TYPE_TO_POINT -> {
                if (dto.pointId() == null) {
                    throw new IllegalArgumentException("TO_POINT 任务必须携带 point_id");
                }
                NavPoint point = navPointMapper.selectById(dto.pointId());
                if (point == null) {
                    throw new NotFoundException("点位 " + dto.pointId() + " 不存在");
                }
                long activeMapId = navMapService.requireActiveMap().getId();
                if (point.getMapId() != activeMapId) {
                    throw new IllegalStateException("点位 " + point.getPointCode()
                            + " 不属于当前激活地图（map_id=" + point.getMapId() + "）");
                }
                task.setPointId(point.getId());
                task.setGoalX(point.getX());
                task.setGoalY(point.getY());
                task.setGoalTheta(point.getYaw());
                segments = List.of(singlePlan(point, dto));
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
                        "STRAIGHT", List.of(), dto.maxSpeed(), false, false));
                endPointSingle = dto.endPoint() == null || dto.endPoint();
            }
            case MoveTask.TYPE_FOLLOW_PATH -> {
                if (dto.pathId() == null) {
                    throw new IllegalArgumentException("FOLLOW_PATH 任务必须携带 path_id");
                }
                NavPath path = navPathService.requirePath(dto.pathId());
                if (!NavPath.STATUS_DEPLOYED.equals(path.getStatus())) {
                    throw new IllegalStateException("路线 " + dto.pathId() + " 未部署（status=" + path.getStatus() + "）");
                }
                segments = buildPathPlan(path.getId(), dto.maxSpeed());
                if (segments.isEmpty()) {
                    throw new IllegalStateException("路线 " + dto.pathId() + " 没有可执行的边");
                }
                SegmentPlan last = segments.get(segments.size() - 1);
                task.setPathId(path.getId());
                task.setGoalX(last.x());
                task.setGoalY(last.y());
                task.setGoalTheta(last.theta());
                endPointSingle = true;
            }
            default -> throw new IllegalArgumentException("不支持的 task_type: " + dto.taskType());
        }

        task.setSegmentTotal(segments.size());
        moveTaskMapper.insert(task);

        TaskRuntime runtime = new TaskRuntime(task, segments, endPointSingle);
        activeRuntimes.put(task.getId(), runtime);
        runtime.watchdog = worker.schedule(() -> onWatchdog(runtime),
                props.getGoalTimeoutSeconds(), TimeUnit.SECONDS);
        dispatchSegment(runtime, 1);
        log.info("move task created: id={}, no={}, type={}, segments={}",
                task.getId(), task.getTaskNo(), task.getTaskType(), segments.size());
        publishTaskEvent(task);
        return MoveTaskVO.from(task);
    }

    /** 取消在途任务：先 cancel_action_goal，终态由 result 确认；超时未确认则强制终结。 */
    public MoveTaskVO cancel(Long id) {
        TaskRuntime runtime = activeRuntimes.get(id);
        if (runtime == null) {
            throw new IllegalStateException("任务 " + id + " 不在执行中，无法取消");
        }
        runtime.cancelRequested = true;
        dispatcher.cancelGoal(runtime.currentGoalId);
        scheduleForceFinalize(runtime, MoveTask.STATUS_CANCELLED, "已下发取消，但未收到 action_result，强制终结");
        log.info("move task cancel requested: id={}, goal={}", id, runtime.currentGoalId);
        return MoveTaskVO.from(runtime.task);
    }

    /** 当前在途任务（无则 data=null）。 */
    public MoveTaskVO active() {
        if (!activeRuntimes.isEmpty()) {
            TaskRuntime runtime = activeRuntimes.values().iterator().next();
            return MoveTaskVO.from(runtime.task);
        }
        MoveTask stale = moveTaskMapper.selectOne(Wrappers.lambdaQuery(MoveTask.class)
                .in(MoveTask::getStatus, MoveTask.STATUS_CREATED, MoveTask.STATUS_DISPATCHED,
                        MoveTask.STATUS_EXECUTING)
                .orderByDesc(MoveTask::getId)
                .last("LIMIT 1"));
        return stale == null ? null : MoveTaskVO.from(stale);
    }

    public List<MoveTaskVO> list(String status, Integer limit) {
        int bounded = limit == null ? 50 : Math.clamp(limit, 1, 200);
        return moveTaskMapper.selectList(Wrappers.lambdaQuery(MoveTask.class)
                        .eq(StringUtils.hasText(status), MoveTask::getStatus, status)
                        .orderByDesc(MoveTask::getId)
                        .last("LIMIT " + bounded))
                .stream().map(MoveTaskVO::from).toList();
    }

    public MoveTaskVO get(Long id) {
        return MoveTaskVO.from(findOrThrow(id));
    }

    // ==================== 内部执行逻辑（worker 线程） ====================

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

        task.setGoalId(goalId);
        task.setSegmentSeq(seq);
        if (!MoveTask.STATUS_EXECUTING.equals(task.getStatus())) {
            task.setStatus(MoveTask.STATUS_DISPATCHED);
        }
        moveTaskMapper.updateById(task);
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

        boolean statusChanged = false;
        if (MoveTask.STATUS_DISPATCHED.equals(task.getStatus())) {
            task.setStatus(MoveTask.STATUS_EXECUTING);
            statusChanged = true;
        }
        long now = System.currentTimeMillis();
        if (statusChanged || now - runtime.lastFeedbackWriteMs >= FEEDBACK_DB_WRITE_INTERVAL_MS) {
            runtime.lastFeedbackWriteMs = now;
            moveTaskMapper.updateById(task);
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
            // FOLLOW_PATH：本段成功，继续下一段（end_point 只在最后一段生效）
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
        task.setStatus(status);
        if (message != null && !message.isBlank()) {
            task.setErrorMessage(message.length() > 500 ? message.substring(0, 500) : message);
        }
        task.setFinishedAt(java.time.LocalDateTime.now());
        moveTaskMapper.updateById(task);
        log.info("move task finished: id={}, no={}, status={}, message={}",
                task.getId(), task.getTaskNo(), status, message);
        publishTaskEvent(task);
    }

    private void publishTaskEvent(MoveTask task) {
        ssePublisher.publishEvent("task", MoveTaskVO.from(task));
    }

    private List<SegmentPlan> buildPathPlan(Long pathId, Double maxSpeedOverride) {
        List<PathEdgeVO> edges = navPathService.loadEdgeVOs(pathId);
        if (edges.isEmpty()) {
            return List.of();
        }
        List<Long> pointIds = edges.stream()
                .flatMap(edge -> Stream.of(edge.getSourcePointId(), edge.getTargetPointId()))
                .distinct()
                .toList();
        Map<Long, NavPoint> points = navPointMapper.selectBatchIds(pointIds).stream()
                .collect(Collectors.toMap(NavPoint::getId, Function.identity()));
        List<SegmentPlan> plans = new ArrayList<>(edges.size());
        for (PathEdgeVO edge : edges) {
            NavPoint target = points.get(edge.getTargetPointId());
            if (target == null) {
                throw new IllegalStateException("路线边引用的点位不存在，数据不一致");
            }
            Double maxSpeed = maxSpeedOverride != null ? maxSpeedOverride : edge.getMaxSpeed();
            List<ControlPoint> controlPoints = edge.getControlPoints() == null ? List.of()
                    : edge.getControlPoints().stream()
                    .map(point -> new ControlPoint(point.getX(), point.getY()))
                    .toList();
            plans.add(new SegmentPlan(edge.getSourcePointCode(), edge.getTargetPointCode(),
                    target.getX(), target.getY(), target.getYaw(),
                    edge.getEdgeType(), controlPoints,
                    maxSpeed,
                    Boolean.TRUE.equals(edge.getBackUp()),
                    Boolean.TRUE.equals(edge.getReverse())));
        }
        return plans;
    }

    private SegmentPlan singlePlan(NavPoint point, MoveTaskCreateDTO dto) {
        return new SegmentPlan("", point.getPointCode(),
                point.getX(), point.getY(), point.getYaw(),
                "STRAIGHT", List.of(), dto.maxSpeed(), false, false);
    }

    private MoveTask findOrThrow(Long id) {
        MoveTask task = moveTaskMapper.selectById(id);
        if (task == null) {
            throw new NotFoundException("任务 " + id + " 不存在");
        }
        return task;
    }

    /** goal 终态映射（独立成方法便于单测）：4 成功 / 5 取消（超时优先记 TIMEOUT）/ 6 或其他失败。 */
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

    /** 一段执行计划：目标点位坐标 + 边参数。 */
    private record SegmentPlan(
            String sourceCode,
            String targetCode,
            double x,
            double y,
            double theta,
            String edgeType,
            List<ControlPoint> controlPoints,
            Double maxSpeed,
            boolean backUp,
            boolean reverse
    ) {
    }

    /** 在途任务运行时。 */
    private static final class TaskRuntime {
        final MoveTask task;
        final List<SegmentPlan> segments;
        /** 单段任务的 end_point（默认 true）；FOLLOW_PATH 恒为最后一段 true。 */
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
