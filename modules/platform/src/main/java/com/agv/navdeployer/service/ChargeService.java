package com.agv.navdeployer.service;

import com.agv.navdeployer.metadata.entity.MapWaypoint;
import com.agv.navdeployer.metadata.mapper.MapWaypointMapper;
import com.agv.navdeployer.sim.RosCommandDispatcher;
import com.agv.navdeployer.sim.SimAgvSsePublisher;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import com.agv.navdeployer.vo.ChargeTaskVO;
import com.agv.navdeployer.vo.MoveTaskVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 充电任务编排（前端「去充电」右键与 RMS charge/replace_battery 任务共用的底层入口）。
 *
 * <p>阶段状态机（对齐机器人侧《电量与充电对接》约定）：
 * <pre>
 * MOVING（智能导航到充电点）→ STARTING（调 /agv/set_charging data=true，等服务 success
 *   并以 /agv/status 遥测 charging=true 确认）→ CHARGING（持续跟踪电量）→ SUCCEEDED（达到
 *   目标电量，留桩不自动断充，下次导航 bridge 自动断充）。
 * 任意阶段：导航失败/被取消、服务拒绝、遥测确认超时、充电状态丢失 → FAILED/CANCELLED。
 * </pre>
 *
 * <p>判定纪律：set_charging 返回 success 只代表「开始充电操作成功」，不是任务完成；
 * 完成只认遥测（battery_data_valid=true 且 battery ≥ 目标电量）。数据无效/过期/未知时
 * 不能判成功——进入宽限计数，超过 stale_grace_ms 判失败。
 */
@Component
public class ChargeService {

    private static final Logger log = LoggerFactory.getLogger(ChargeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** SSE 发布保活间隔：无状态/电量变化也周期推一次，防前端断连窗口丢事件 */
    private static final long PUBLISH_KEEPALIVE_MS = 30000L;

    /** 充电指令：waypointId（v2 充电点）优先，缺省用显式坐标；targetBatteryPct null=用默认配置。 */
    public record ChargeCommand(String source, Long waypointId,
                                Double x, Double y, Double yaw, Double targetBatteryPct) {
    }

    private final SmartNavigationService smartNavigation;
    private final MoveTaskService moveTaskService;
    private final RosCommandDispatcher dispatcher;
    private final SimAgvTelemetry telemetry;
    private final SimAgvSsePublisher ssePublisher;
    private final ChargeProperties props;
    private final MapWaypointMapper v2WaypointMapper;
    /** 机械臂归位/停用闸门已由 MoveTaskService.create 统一执行（可空：独立装配） */
    private final RobotOperationalService operationalService;

    /** 状态机线程：轮询与回调串行执行，状态非终态字段只在该线程写 */
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "charge-worker");
                thread.setDaemon(true);
                return thread;
            });

    private final AtomicLong idSeq = new AtomicLong(System.currentTimeMillis());
    /** 单机器人单充电任务：当前实例（终态后保留供查询，新任务覆盖） */
    private volatile ChargeTask current;
    /** 终态任务历史（内存环形，最新在前；任务管理页展示用，重启即清） */
    private final java.util.ArrayDeque<ChargeTaskVO> history = new java.util.ArrayDeque<>();
    private static final int HISTORY_CAPACITY = 100;

    public ChargeService(SmartNavigationService smartNavigation,
                         MoveTaskService moveTaskService,
                         RosCommandDispatcher dispatcher,
                         SimAgvTelemetry telemetry,
                         SimAgvSsePublisher ssePublisher,
                         ChargeProperties props,
                         MapWaypointMapper v2WaypointMapper,
                         RobotOperationalService operationalService) {
        this.smartNavigation = smartNavigation;
        this.moveTaskService = moveTaskService;
        this.dispatcher = dispatcher;
        this.telemetry = telemetry;
        this.ssePublisher = ssePublisher;
        this.props = props;
        this.v2WaypointMapper = v2WaypointMapper;
        this.operationalService = operationalService;
    }

    // ==================== 对外入口 ====================

    /**
     * 发起充电：导航到充电点 → 开始充电 → 充到目标电量。
     * 闸门失败（连接/定位/占用/机械臂未归位等）由 MoveTaskService.create 抛出 IllegalArgumentException。
     */
    public synchronized ChargeTaskVO start(ChargeCommand command) {
        ChargeTask existing = current;
        if (existing != null && !existing.status().terminal()) {
            throw new IllegalStateException("已有进行中的充电任务（id=" + existing.id()
                    + ", status=" + existing.status() + "），请先取消");
        }
        if (operationalService != null && operationalService.isDecommissioned()) {
            throw new IllegalStateException("机器人已停用（" + operationalService.getReason() + "），无法发起充电");
        }
        // 目标位姿：优先 v2 充电点位 id，缺省用显式坐标
        Long waypointId = command.waypointId();
        double x;
        double y;
        double yaw;
        if (waypointId != null) {
            MapWaypoint waypoint = v2WaypointMapper.selectById(waypointId);
            if (waypoint == null) {
                throw new IllegalArgumentException("充电点不存在: waypoint_id=" + waypointId);
            }
            x = waypoint.getX();
            y = waypoint.getY();
            yaw = waypoint.getYaw() == null ? 0.0 : waypoint.getYaw();
        } else if (command.x() != null && command.y() != null) {
            x = command.x();
            y = command.y();
            yaw = command.yaw() == null ? 0.0 : command.yaw();
        } else {
            throw new IllegalArgumentException("缺少充电目标（waypoint_id 或 x/y）");
        }
        double target = command.targetBatteryPct() != null
                ? command.targetBatteryPct() : props.getTargetBatteryPct();
        if (target <= 0.0 || target > 100.0) {
            throw new IllegalArgumentException("target_battery_pct 必须在 (0, 100]，当前: " + target);
        }

        // 智能导航到充电点（闸门复用 MoveTaskService：连接/定位/模式/单任务互斥/机械臂归位）。
        // 路线导航走完整条链：多 path 由 MOVING 轮询逐段接力，全链走完才到桩开始充电
        java.util.List<Long> routePathIds = smartNavigation.planRoutePathIds("charge", x, y);
        MoveTaskVO moveTask;
        ChargeTask task = new ChargeTask(idSeq.incrementAndGet(), command.source(),
                waypointId, x, y, yaw, target);
        if (routePathIds.isEmpty()) {
            moveTask = smartNavigation.createGoalTask(x, y, yaw);
        } else {
            task.offerRemainingPaths(routePathIds);
            moveTask = smartNavigation.createFollowPathTask(routePathIds.get(0));
        }
        task.setInternalMoveTaskId(moveTask.getId());
        task.setBatteryNow(currentBatteryPct());
        this.current = task;
        log.info("charge task {} start: source={} target=({},{},{}rad) targetBattery={}% moveTask={} (type={}, segments={})",
                task.id(), command.source(), x, y, yaw, target, moveTask.getId(),
                moveTask.getTaskType(), routePathIds.isEmpty() ? 1 : routePathIds.size());
        publish(task, true);
        worker.schedule(this::poll, props.getPollIntervalMs(), TimeUnit.MILLISECONDS);
        return task.vo();
    }

    /** 进行中的充电任务（无或已终态返回 null）。 */
    public ChargeTaskVO active() {
        ChargeTask task = current;
        return task != null && !task.status().terminal() ? task.vo() : null;
    }

    /** 按 id 查询（RMS 轮询用）；id 不匹配（已被新任务覆盖）返回 null。 */
    public ChargeTaskVO get(long id) {
        ChargeTask task = current;
        return task != null && task.id() == id ? task.vo() : null;
    }

    /**
     * 救援充电：跳过导航，直接远程调 set_charging 开始充电。
     * 适用：电量耗尽机器人拒绝一切移动指令、无法导航到桩的死锁场景
     * （机器人侧不校验是否在桩上；真机 BMS 接入后此接口自然失效——真机 0% 需人工推桩）。
     * 流程仍走完整状态机：停稳等待 → 服务受理 → 遥测确认 → 充到目标电量。
     */
    public synchronized ChargeTaskVO startRescue() {
        ChargeTask existing = current;
        if (existing != null && !existing.status().terminal()) {
            throw new IllegalStateException("已有进行中的充电任务（id=" + existing.id()
                    + "，status=" + existing.status() + "），请先取消");
        }
        if (operationalService != null && operationalService.isDecommissioned()) {
            throw new IllegalStateException("机器人已停用（" + operationalService.getReason() + "），无法发起救援充电");
        }
        ChargeTask task = new ChargeTask(idSeq.incrementAndGet(), "rescue", null,
                Double.NaN, Double.NaN, 0.0, props.getTargetBatteryPct());
        task.setBatteryNow(currentBatteryPct());
        this.current = task;
        log.info("charge task {} RESCUE start: 远程开始充电（机器人未导航到桩，低电量死锁救援）, targetBattery={}%",
                task.id(), task.targetBatteryPct());
        // 不经 MOVING：直接进入「开始充电」阶段（停稳等待 + 服务 + 遥测确认），后续与正常充电一致
        beginStartCharging(task);
        publish(task, true);
        worker.schedule(this::poll, props.getPollIntervalMs(), TimeUnit.MILLISECONDS);
        return task.vo();
    }

    /** 取消充电：MOVING 阶段连带取消内部移动；已在桩上则尽力调用停止充电（data=false）。 */
    public synchronized ChargeTaskVO cancel(String reason) {
        ChargeTask task = current;
        if (task == null || task.status().terminal()) {
            throw new IllegalStateException("没有进行中的充电任务");
        }
        String finalReason = reason == null || reason.isBlank() ? "手动取消" : reason;
        ChargeTask.Status phase = task.status();
        if (!task.transitionTo(ChargeTask.Status.CANCELLED)) {
            return task.vo();
        }
        task.setMessage(finalReason);
        task.stamp(ChargeTask.Status.CANCELLED);
        log.info("charge task {} cancelled at phase {}: {}", task.id(), phase, finalReason);
        if (phase == ChargeTask.Status.MOVING && task.internalMoveTaskId() != null) {
            Long moveTaskId = task.internalMoveTaskId();
            worker.execute(() -> cancelMoveQuietly(moveTaskId));
        } else if (phase == ChargeTask.Status.STARTING || phase == ChargeTask.Status.CHARGING) {
            // 取消时显式断充（成功到桩的路径才需要；新移动任务触发时 bridge 也会自动断充）
            callSetCharging(false);
        }
        publish(task, true);
        recordHistory(task);
        return task.vo();
    }

    /** 充电任务历史（最新在前，内存环形；含当前进行中的任务置顶）。 */
    public synchronized java.util.List<ChargeTaskVO> history(int limit) {
        java.util.List<ChargeTaskVO> snapshot = new java.util.ArrayList<>(Math.max(1, limit));
        ChargeTask active = current;
        if (active != null && !active.status().terminal()) {
            snapshot.add(active.vo());
        }
        synchronized (history) {
            for (ChargeTaskVO item : history) {
                if (snapshot.size() >= Math.max(1, Math.min(limit, HISTORY_CAPACITY))) {
                    break;
                }
                snapshot.add(item);
            }
        }
        return snapshot;
    }

    private void recordHistory(ChargeTask task) {
        synchronized (history) {
            history.addFirst(task.vo());
            while (history.size() > HISTORY_CAPACITY) {
                history.pollLast();
            }
        }
    }

    // ==================== 状态机（charge-worker 线程） ====================

    private void poll() {
        try {
            ChargeTask task = current;
            if (task == null || task.status().terminal()) {
                return;
            }
            // 全流程看门狗（导航 + 充电总时长）
            if (System.currentTimeMillis() - task.createdAtMs() > props.getMaxDurationMs()) {
                finish(task, ChargeTask.Status.FAILED,
                        "充电任务超时（超过 max_duration_ms=" + props.getMaxDurationMs() + "ms）");
                return;
            }
            switch (task.status()) {
                case MOVING -> pollMoving(task);
                case STARTING -> pollStarting(task);
                case CHARGING -> pollCharging(task);
                default -> {
                }
            }
        } catch (Throwable throwable) {
            log.warn("charge poll error: {}", throwable.toString());
        }
        ChargeTask task = current;
        if (task != null && !task.status().terminal()) {
            worker.schedule(this::poll, props.getPollIntervalMs(), TimeUnit.MILLISECONDS);
        }
    }

    private void pollMoving(ChargeTask task) {
        MoveTaskVO moveTask;
        try {
            moveTask = moveTaskService.get(task.internalMoveTaskId());
        } catch (Exception exception) {
            log.warn("charge task {} poll move task {} failed: {}",
                    task.id(), task.internalMoveTaskId(), exception.getMessage());
            return;
        }
        String status = moveTask.getStatus() == null ? "" : moveTask.getStatus();
        switch (status) {
            case "SUCCEEDED" -> dispatchNextSegmentOrDock(task);
            case "FAILED", "TIMEOUT" -> finish(task, ChargeTask.Status.FAILED,
                    "导航到充电点失败: " + (moveTask.getErrorMessage() == null
                            || moveTask.getErrorMessage().isBlank()
                            ? status : moveTask.getErrorMessage()));
            case "CANCELLED" -> finish(task, ChargeTask.Status.CANCELLED, "导航到充电点被取消");
            default -> {
            }
        }
    }

    /**
     * 当前路线段走完：还有剩余 path 就接力下发下一段（FOLLOW_PATH），
     * 整条链走完才调 set_charging 开始充电。
     */
    private void dispatchNextSegmentOrDock(ChargeTask task) {
        Long nextPathId = task.pollNextPathId();
        if (nextPathId == null) {
            beginStartCharging(task);
            return;
        }
        try {
            MoveTaskVO next = smartNavigation.createFollowPathTask(nextPathId);
            task.setInternalMoveTaskId(next.getId());
            task.stamp(null);
            String progress = task.segmentProgress();
            task.setMessage("导航到充电点（" + progress + "）");
            log.info("charge task {} segment done ({}), dispatch next FOLLOW_PATH pathId={} as moveTask={}",
                    task.id(), task.segmentProgress(), nextPathId, next.getId());
            publish(task, true);
        } catch (Exception exception) {
            // 接力段闸门失败（连接断开/被顶占等）：整条链中断，按失败收口
            finish(task, ChargeTask.Status.FAILED,
                    "下发下一段路线失败（" + task.segmentProgress() + "）: " + exception.getMessage());
        }
    }

    /**
     * 导航链走完、到桩：先进入停稳等待（机器人侧 set_charging 会拒绝运动中的请求——
     * 移动任务 SUCCEEDED 后末端旋转/减速仍有残余速度，到位即调会被拒），
     * 真正调服务由 pollStarting 在「最小等待 + odom 静止」后触发。
     */
    private void beginStartCharging(ChargeTask task) {
        if (!task.transitionTo(ChargeTask.Status.STARTING)) {
            return;
        }
        task.stamp(ChargeTask.Status.STARTING);
        task.setMessage("已到充电点，等待停车稳定");
        task.setDockedAt(System.currentTimeMillis());
        publish(task, true);
        log.info("charge task {} navigation done, waiting robot to settle (min={}ms max={}ms)",
                task.id(), props.getDockSettleMinMs(), props.getDockSettleMaxMs());
    }

    /** 停稳判定通过，真正调用机器人侧开始充电服务（std_srvs/SetBool data=true）。 */
    private void sendStartCharging(ChargeTask task) {
        task.setServiceSentAt(System.currentTimeMillis());
        task.setMessage("正在开始充电");
        publish(task, true);
        log.info("charge task {} settled, calling {} data=true", task.id(), props.getService());
        CompletableFuture<JsonNode> call;
        try {
            ObjectNode args = MAPPER.createObjectNode();
            args.put("data", true);
            call = dispatcher.callService(props.getService(), args, props.getServiceTimeoutMs());
        } catch (Exception exception) {
            finish(task, ChargeTask.Status.FAILED, "开始充电服务下发失败: " + exception.getMessage());
            return;
        }
        call.whenComplete((values, error) -> worker.execute(() ->
                onChargeServiceResult(task, values, error)));
    }

    /** set_charging(data=true) 结果（worker 线程）。 */
    private void onChargeServiceResult(ChargeTask task, JsonNode values, Throwable error) {
        if (task.status().terminal()) {
            return;
        }
        if (error != null) {
            finish(task, ChargeTask.Status.FAILED,
                    "开始充电服务调用失败: " + rootMessage(error));
            return;
        }
        boolean success = values != null && values.path("success").asBoolean(false);
        if (!success) {
            finish(task, ChargeTask.Status.FAILED, "机器人拒绝开始充电: "
                    + (values == null ? "null" : values.path("message").asText("")));
            return;
        }
        // 服务受理成功 ≠ 充电中：等遥测 charging=true 确认（pollStarting 检查）
        task.setServiceAckAt(System.currentTimeMillis());
        log.info("charge task {} set_charging accepted, waiting telemetry confirm", task.id());
    }

    private void pollStarting(ChargeTask task) {
        // 阶段一：停稳等待——满足「最小等待 + odom 静止」才调 set_charging；
        // odom 未启用/未收到数据时无法判静止，退化为纯最小等待；超过等待上限强制尝试（兜底）
        if (task.serviceSentAt() == 0) {
            long elapsed = System.currentTimeMillis() - task.dockedAt();
            boolean quiet = isRobotSettled();
            if ((elapsed >= props.getDockSettleMinMs() && quiet) || elapsed >= props.getDockSettleMaxMs()) {
                sendStartCharging(task);
            }
            return;
        }
        // 阶段二：服务已发出——等 ack + 遥测 charging=true 确认
        if (task.serviceAckAt() > 0) {
            SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
            if (status != null && Boolean.TRUE.equals(status.charging())) {
                task.setBatteryAtStart(status.battery());
                task.setFirstChargeLostAt(0);
                if (task.transitionTo(ChargeTask.Status.CHARGING)) {
                    task.stamp(ChargeTask.Status.CHARGING);
                    task.setMessage("充电中");
                    log.info("charge task {} charging confirmed: battery={}%",
                            task.id(), formatBattery(status.battery()));
                    publish(task, true);
                }
                return;
            }
            if (System.currentTimeMillis() - task.serviceAckAt() > props.getConfirmTimeoutMs()) {
                finish(task, ChargeTask.Status.FAILED,
                        "充电启动未确认：服务成功但 " + props.getConfirmTimeoutMs()
                                + "ms 内 /agv/status 未上报 charging=true");
            }
            return;
        }
        // 服务未回（future 泄漏兜底，理论上 orTimeout 会异常完成走 onChargeServiceResult）
        if (task.serviceSentAt() > 0
                && System.currentTimeMillis() - task.serviceSentAt() > props.getServiceTimeoutMs() + 3000L) {
            finish(task, ChargeTask.Status.FAILED, "开始充电服务无响应");
        }
    }

    private void pollCharging(ChargeTask task) {
        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        double battery = status == null ? Double.NaN : status.battery();
        task.setBatteryNow(battery);
        publish(task, false);

        // 完成：只认有效遥测（battery_data_valid != false 且数值有效）
        if (status != null && !Boolean.FALSE.equals(status.batteryDataValid())
                && !Double.isNaN(battery) && battery >= task.targetBatteryPct()) {
            finish(task, ChargeTask.Status.SUCCEEDED,
                    "已达到目标电量 " + formatBattery(battery) + "%（目标 "
                            + task.targetBatteryPct() + "%），留桩待命");
            return;
        }

        // 充电真值丢失：优先看是否被新移动任务打断（bridge 自动断充）
        Boolean charging = status == null ? null : status.charging();
        if (charging != null && !charging) {
            boolean moveActive = false;
            try {
                moveActive = moveTaskService.active() != null;
            } catch (Exception ignored) {
            }
            if (moveActive) {
                finish(task, ChargeTask.Status.CANCELLED,
                        "充电被新的移动任务打断（bridge 已自动断充）");
                return;
            }
            if (task.firstChargeLostAt() == 0) {
                task.setFirstChargeLostAt(System.currentTimeMillis());
            } else if (System.currentTimeMillis() - task.firstChargeLostAt() > props.getStaleGraceMs()) {
                finish(task, ChargeTask.Status.FAILED,
                        "充电状态丢失（charging=false 持续超过 " + props.getStaleGraceMs() + "ms 且无移动任务）");
                return;
            }
        } else if (charging != null) {
            task.setFirstChargeLostAt(0);
        }
        // 数据无效/未知（battery_data_valid=false、遥测过期）：不能判成功，继续等待，
        // 由全流程看门狗与 stale 宽限兜底
    }

    /** 终态迁移 + 日志 + SSE 推送（幂等：已终态忽略）。 */
    private void finish(ChargeTask task, ChargeTask.Status status, String message) {
        if (!task.transitionTo(status)) {
            return;
        }
        task.stamp(status);
        task.setMessage(message);
        log.info("charge task {} -> {}: {}", task.id(), status, message);
        publish(task, true);
        recordHistory(task);
    }

    // ==================== 辅助 ====================

    /**
     * 机器人静止判定：/odom twist 接近零速视为停稳；
     * odom 未启用/未收到数据（getOdomCount=0）时返回 true（视为不可判，走纯延时）。
     */
    private boolean isRobotSettled() {
        if (telemetry.getOdomCount() <= 0) {
            return true;
        }
        return Math.abs(telemetry.getOdomLinearX()) < props.getOdomQuietLinearMs()
                && Math.abs(telemetry.getOdomLinearY()) < props.getOdomQuietLinearMs()
                && Math.abs(telemetry.getOdomAngularZ()) < props.getOdomQuietAngularRad();
    }

    private void callSetCharging(boolean data) {
        try {
            ObjectNode args = MAPPER.createObjectNode();
            args.put("data", data);
            dispatcher.callService(props.getService(), args, props.getServiceTimeoutMs())
                    .whenComplete((values, error) -> {
                        if (error != null) {
                            log.warn("set_charging({}) failed: {}", data, rootMessage(error));
                        } else {
                            log.info("set_charging({}) -> success={} message={}", data,
                                    values.path("success").asBoolean(false),
                                    values.path("message").asText(""));
                        }
                    });
        } catch (Exception exception) {
            log.warn("set_charging({}) dispatch failed: {}", data, exception.getMessage());
        }
    }

    private void cancelMoveQuietly(Long moveTaskId) {
        try {
            moveTaskService.cancel(moveTaskId);
        } catch (Exception exception) {
            log.debug("cancel internal move task {} for charge task: {}",
                    moveTaskId, exception.getMessage());
        }
    }

    private double currentBatteryPct() {
        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        double battery = status == null ? Double.NaN : status.battery();
        return Double.isNaN(battery) ? Double.NaN : battery;
    }

    /** SSE 推送（带变化检测：状态变更 / 电量变化≥0.5% / 30s 保活），force 立即推。 */
    private void publish(ChargeTask task, boolean force) {
        long now = System.currentTimeMillis();
        boolean statusChanged = task.status() != task.lastPublishedStatus;
        double battery = task.batteryNow();
        boolean batteryChanged = !Double.isNaN(battery)
                && (Double.isNaN(task.lastPublishedBattery)
                || Math.abs(battery - task.lastPublishedBattery) >= 0.5);
        if (!(force || statusChanged || batteryChanged || now - task.lastPublishedAt >= PUBLISH_KEEPALIVE_MS)) {
            return;
        }
        task.lastPublishedStatus = task.status();
        task.lastPublishedBattery = battery;
        task.lastPublishedAt = now;
        try {
            ssePublisher.publishEvent("charge", task.vo());
        } catch (Exception exception) {
            log.debug("publish charge event failed: {}", exception.getMessage());
        }
    }

    private static String formatBattery(double battery) {
        return Double.isNaN(battery) ? "n/a" : String.format(java.util.Locale.ROOT, "%.1f", battery);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
