package com.agv.navdeployer.task.application;

import com.agv.navdeployer.map.application.MapMetaService;
import com.agv.navdeployer.map.domain.MapWaypoint;
import com.agv.navdeployer.task.domain.port.RobotCommandPort;
import com.agv.navdeployer.task.domain.port.RobotEventPort;
import com.agv.navdeployer.task.domain.port.RobotTelemetryPort;
import com.agv.navdeployer.task.application.cmd.ChargeStartCmd;
import com.agv.navdeployer.task.application.dto.ChargeTaskDTO;
import com.agv.navdeployer.task.domain.charge.ChargeProperties;
import com.agv.navdeployer.task.domain.charge.ChargeTask;
import com.agv.navdeployer.task.application.dto.MoveTaskDTO;
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

@Component
public class ChargeService {

    private static final Logger log = LoggerFactory.getLogger(ChargeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long PUBLISH_KEEPALIVE_MS = 30000L;

    private final SmartNavigationService smartNavigation;
    private final MoveTaskService moveTaskService;
    private final RobotCommandPort robotCommands;
    private final RobotTelemetryPort telemetry;
    private final RobotEventPort events;
    private final ChargeProperties props;
    private final MapMetaService mapMetaService;
    private final RobotOperationalService operationalService;

    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "charge-worker");
                thread.setDaemon(true);
                return thread;
            });

    private final AtomicLong idSeq = new AtomicLong(System.currentTimeMillis());
    private volatile ChargeTask current;
    private final java.util.ArrayDeque<ChargeTaskDTO> history = new java.util.ArrayDeque<>();
    private static final int HISTORY_CAPACITY = 100;

    public ChargeService(SmartNavigationService smartNavigation,
                         MoveTaskService moveTaskService,
                         RobotCommandPort robotCommands,
                         RobotTelemetryPort telemetry,
                         RobotEventPort events,
                         ChargeProperties props,
                         MapMetaService mapMetaService,
                         RobotOperationalService operationalService) {
        this.smartNavigation = smartNavigation;
        this.moveTaskService = moveTaskService;
        this.robotCommands = robotCommands;
        this.telemetry = telemetry;
        this.events = events;
        this.props = props;
        this.mapMetaService = mapMetaService;
        this.operationalService = operationalService;
    }

    public synchronized ChargeTaskDTO start(ChargeStartCmd command) {
        ChargeTask existing = current;
        if (existing != null && !existing.status().terminal()) {
            throw new IllegalStateException("已有进行中的充电任务（id=" + existing.id()
                    + ", status=" + existing.status() + "），请先取消");
        }
        if (operationalService != null && operationalService.isDecommissioned()) {
            throw new IllegalStateException("机器人已停用（" + operationalService.getReason() + "），无法发起充电");
        }
        Long waypointId = command.waypointId();
        double x;
        double y;
        double yaw;
        if (waypointId != null) {
            MapWaypoint waypoint = mapMetaService.getWaypoint(waypointId);
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

        ChargeTask task = new ChargeTask(idSeq.incrementAndGet(), command.source(),
                waypointId, x, y, yaw, target);
        MoveTaskDTO moveTask = smartNavigation.createOptimizedMoveTask(
                "charge-" + task.id(), x, y, yaw, "charge:" + task.id());
        task.setInternalMoveTaskId(moveTask.id());
        task.setBatteryNow(currentBatteryPct());
        this.current = task;
        log.info("charge task {} start: source={} target=({},{},{}rad) targetBattery={}% moveTask={} (type={})",
                task.id(), command.source(), x, y, yaw, target, moveTask.id(), moveTask.taskType());
        publish(task, true);
        worker.schedule(this::poll, props.getPollIntervalMs(), TimeUnit.MILLISECONDS);
        return vo(task);
    }
    public ChargeTaskDTO active() {
        ChargeTask task = current;
        return task != null && !task.status().terminal() ? vo(task) : null;
    }

    public ChargeTaskDTO get(long id) {
        ChargeTask task = current;
        return task != null && task.id() == id ? vo(task) : null;
    }

    public synchronized ChargeTaskDTO startRescue() {
        ChargeTaskDTO task = chargeInPlace("rescue", null, true, true);
        log.info("charge task {} RESCUE: 模拟接上充电器原地开始充电", task.id());
        return task;
    }

    /**
     * 原地充电（无导航）：RMS 调度分段导航就位后执行（persistCharging=false），
     * 或前端救援充电=模拟插充电器（persistCharging=true，充电确认后进入 CHARGING 持续态）。
     * requireIdleRobot=true 时前置校验底盘/机械臂静止（真机插充电器要求完全静止）。
     */
    public synchronized ChargeTaskDTO chargeInPlace(String source, Double targetBatteryPct,
                                                    boolean persistCharging, boolean requireIdleRobot) {
        ChargeTask existing = current;
        if (existing != null && !existing.status().terminal()) {
            throw new IllegalStateException("已有进行中的充电任务（id=" + existing.id()
                    + "，status=" + existing.status() + "），请先取消");
        }
        if (operationalService != null && operationalService.isDecommissioned()) {
            throw new IllegalStateException("机器人已停用（" + operationalService.getReason() + "），无法发起充电");
        }
        if (moveTaskService.hasActiveTask()) {
            throw new IllegalStateException("存在在途移动任务，无法原地开始充电"
                    + (source != null && source.startsWith("rms") ? "（调度未完成分段导航）" : "，请先取消或等待完成"));
        }
        if (requireIdleRobot) {
            requireRobotIdleForRescue();
        }
        double target = targetBatteryPct != null ? targetBatteryPct : props.getTargetBatteryPct();
        if (target <= 0.0 || target > 100.0) {
            throw new IllegalArgumentException("target_battery_pct 必须在 (0, 100]，当前: " + target);
        }
        ChargeTask task = new ChargeTask(idSeq.incrementAndGet(), source, null,
                Double.NaN, Double.NaN, 0.0, target);
        task.setPersistCharging(persistCharging);
        task.setBatteryNow(currentBatteryPct());
        this.current = task;
        log.info("charge task {} IN-PLACE start: source={} 原地开始充电, persistCharging={}, targetBattery={}%",
                task.id(), source, persistCharging, target);
        beginStartCharging(task);
        publish(task, true);
        worker.schedule(this::poll, props.getPollIntervalMs(), TimeUnit.MILLISECONDS);
        return vo(task);
    }

    private void requireRobotIdleForRescue() {
        if (telemetry.odomCount() > 0
                && !(Math.abs(telemetry.odomLinearX()) < props.getOdomQuietLinearMs()
                && Math.abs(telemetry.odomLinearY()) < props.getOdomQuietLinearMs()
                && Math.abs(telemetry.odomAngularZ()) < props.getOdomQuietAngularRad())) {
            throw new IllegalStateException("底盘运动中，无法执行救援充电（真机要求完全静止才可接充电器）");
        }
        if (!telemetry.armMovementAllowed()) {
            throw new IllegalStateException("机械臂未静止/未归位（arm_state=" + telemetry.armState()
                    + "），无法执行救援充电");
        }
    }

    public synchronized ChargeTaskDTO cancel(String reason) {
        ChargeTask task = current;
        if (task == null || task.status().terminal()) {
            throw new IllegalStateException("没有进行中的充电任务");
        }
        String finalReason = reason == null || reason.isBlank() ? "手动取消" : reason;
        ChargeTask.Status phase = task.status();
        if (!task.transitionTo(ChargeTask.Status.CANCELLED)) {
            return vo(task);
        }
        task.setMessage(finalReason);
        task.stamp(ChargeTask.Status.CANCELLED);
        log.info("charge task {} cancelled at phase {}: {}", task.id(), phase, finalReason);
        if (phase == ChargeTask.Status.MOVING && task.internalMoveTaskId() != null) {
            Long moveTaskId = task.internalMoveTaskId();
            worker.execute(() -> cancelMoveQuietly(moveTaskId));
        } else if (phase == ChargeTask.Status.STARTING || phase == ChargeTask.Status.CHARGING) {
            callSetCharging(false);
        }
        publish(task, true);
        recordHistory(task);
        return vo(task);
    }

    public void stopCharging() {
        ChargeTask task = current;
        if (task != null && task.status() == ChargeTask.Status.CHARGING) {
            log.info("stop charging requested: charge task {} manual stop, calling {} data=false",
                    task.id(), props.getService());
            callSetCharging(false);
            finish(task, ChargeTask.Status.SUCCEEDED, "手动停止充电");
            return;
        }
        log.info("stop charging requested: calling {} data=false", props.getService());
        callSetCharging(false);
    }

    /** 急停联动：CHARGING 会话以 CANCELLED 收口，无论如何都发断充指令 */
    public synchronized void emergencyInterrupt() {
        ChargeTask task = current;
        callSetCharging(false);
        if (task != null && task.status() == ChargeTask.Status.CHARGING) {
            finish(task, ChargeTask.Status.CANCELLED, "急停联动断充");
        }
    }

    public synchronized java.util.List<ChargeTaskDTO> history(int limit) {
        java.util.List<ChargeTaskDTO> snapshot = new java.util.ArrayList<>(Math.max(1, limit));
        ChargeTask active = current;
        if (active != null && !active.status().terminal()) {
            snapshot.add(vo(active));
        }
        synchronized (history) {
            for (ChargeTaskDTO item : history) {
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
            history.addFirst(vo(task));
            while (history.size() > HISTORY_CAPACITY) {
                history.pollLast();
            }
        }
    }

    private void poll() {
        try {
            ChargeTask task = current;
            if (task == null || task.status().terminal()) {
                return;
            }
            if (System.currentTimeMillis() - task.createdAtMs() > props.getMaxDurationMs()
                    && task.status() != ChargeTask.Status.CHARGING) {
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
        MoveTaskDTO moveTask;
        try {
            moveTask = moveTaskService.get(task.internalMoveTaskId());
        } catch (Exception exception) {
            log.warn("charge task {} poll move task {} failed: {}",
                    task.id(), task.internalMoveTaskId(), exception.getMessage());
            return;
        }
        String status = moveTask.status() == null ? "" : moveTask.status();
        switch (status) {
            case "SUCCEEDED" -> {
                // move 终点必须就是充电点（同点一致性防呆，非到桩精度；防规划/路线数据异常导致错误位置开充）
                if (moveTask.goalX() != null && moveTask.goalY() != null
                        && !Double.isNaN(task.x()) && !Double.isNaN(task.y())
                        && Math.hypot(moveTask.goalX() - task.x(), moveTask.goalY() - task.y()) > 0.5) {
                    finish(task, ChargeTask.Status.FAILED, String.format(java.util.Locale.ROOT,
                            "导航终点(%.2f,%.2f)与充电点(%.2f,%.2f)不一致，已拒绝开始充电（路线/规划数据异常）",
                            moveTask.goalX(), moveTask.goalY(), task.x(), task.y()));
                    return;
                }
                beginStartCharging(task);
            }
            case "FAILED", "TIMEOUT" -> finish(task, ChargeTask.Status.FAILED,
                    "导航到充电点失败: " + (moveTask.errorMessage() == null
                            || moveTask.errorMessage().isBlank()
                            ? status : moveTask.errorMessage()));
            case "CANCELLED" -> finish(task, ChargeTask.Status.CANCELLED, "导航到充电点被取消");
            default -> {
            }
        }
    }

    private void beginStartCharging(ChargeTask task) {
        if (!task.transitionTo(ChargeTask.Status.STARTING)) {
            return;
        }
        task.stamp(ChargeTask.Status.STARTING);
        task.setMessage(task.persistCharging() ? "模拟接上充电器，准备开始充电" : "已到充电点，等待停车稳定");
        task.setDockedAt(System.currentTimeMillis());
        publish(task, true);
        log.info("charge task {} ready, waiting robot to settle (min={}ms max={}ms)",
                task.id(), props.getDockSettleMinMs(), props.getDockSettleMaxMs());
    }

    private void sendStartCharging(ChargeTask task) {
        task.setServiceSentAt(System.currentTimeMillis());
        task.setMessage("正在开始充电");
        publish(task, true);
        log.info("charge task {} settled, calling {} data=true", task.id(), props.getService());
        CompletableFuture<JsonNode> call;
        try {
            ObjectNode args = MAPPER.createObjectNode();
            args.put("data", true);
            call = robotCommands.callService(props.getService(), args, props.getServiceTimeoutMs());
        } catch (Exception exception) {
            finish(task, ChargeTask.Status.FAILED, "开始充电服务下发失败: " + exception.getMessage());
            return;
        }
        call.whenComplete((values, error) -> worker.execute(() ->
                onChargeServiceResult(task, values, error)));
    }

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
        if (task.serviceSentAt() == 0) {
            if (task.persistCharging()) {
                sendStartCharging(task);
                return;
            }
            long elapsed = System.currentTimeMillis() - task.dockedAt();
            boolean quiet = isRobotSettled();
            if ((elapsed >= props.getDockSettleMinMs() && quiet) || elapsed >= props.getDockSettleMaxMs()) {
                sendStartCharging(task);
            }
            return;
        }
        if (task.serviceAckAt() > 0) {
            var status = telemetry.status();
            if (status != null && Boolean.TRUE.equals(status.charging())) {
                task.setBatteryAtStart(status.battery());
                if (task.persistCharging() && task.transitionTo(ChargeTask.Status.CHARGING)) {
                    task.stamp(ChargeTask.Status.CHARGING);
                    task.setMessage(String.format(java.util.Locale.ROOT,
                            "充电中（当前电量 %s%%，充满或断开后自动结束）", formatBattery(status.battery())));
                    publish(task, true);
                    log.info("charge task {} -> CHARGING (rescue session)", task.id());
                    return;
                }
                finish(task, ChargeTask.Status.SUCCEEDED, String.format(java.util.Locale.ROOT,
                        "已对接充电桩，充电已开始（当前电量 %s%%）", formatBattery(status.battery())));
                return;
            }
            if (System.currentTimeMillis() - task.serviceAckAt() > props.getConfirmTimeoutMs()) {
                finish(task, ChargeTask.Status.FAILED,
                        "充电启动未确认：服务成功但 " + props.getConfirmTimeoutMs()
                                + "ms 内 /agv/status 未上报 charging=true");
            }
            return;
        }
        if (task.serviceSentAt() > 0
                && System.currentTimeMillis() - task.serviceSentAt() > props.getServiceTimeoutMs() + 3000L) {
            finish(task, ChargeTask.Status.FAILED, "开始充电服务无响应");
        }
    }

    private void pollCharging(ChargeTask task) {
        var status = telemetry.status();
        if (status == null) {
            return;
        }
        if (Boolean.TRUE.equals(status.charging())) {
            task.setBatteryNow(status.battery());
            publish(task, false);
            return;
        }
        double battery = status.battery() == null ? Double.NaN : status.battery();
        if (!Double.isNaN(battery) && battery >= 99.0) {
            finish(task, ChargeTask.Status.SUCCEEDED, "已充满，自动断开充电");
        } else if (moveTaskService.hasActiveTask()) {
            finish(task, ChargeTask.Status.CANCELLED, "任务插队，已自动断开充电");
        } else {
            finish(task, ChargeTask.Status.FAILED,
                    "充电异常中断（未充满，当前电量 " + formatBattery(battery) + "%）");
        }
    }

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

    private boolean isRobotSettled() {
        if (telemetry.odomCount() <= 0) {
            return true;
        }
        return Math.abs(telemetry.odomLinearX()) < props.getOdomQuietLinearMs()
                && Math.abs(telemetry.odomLinearY()) < props.getOdomQuietLinearMs()
                && Math.abs(telemetry.odomAngularZ()) < props.getOdomQuietAngularRad();
    }

    private void callSetCharging(boolean data) {
        try {
            ObjectNode args = MAPPER.createObjectNode();
            args.put("data", data);
            robotCommands.callService(props.getService(), args, props.getServiceTimeoutMs())
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
        var status = telemetry.status();
        double battery = status == null ? Double.NaN : status.battery();
        return Double.isNaN(battery) ? Double.NaN : battery;
    }

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
            events.publish("charge", vo(task));
        } catch (Exception exception) {
            log.debug("publish charge event failed: {}", exception.getMessage());
        }
    }

    private static String formatBattery(double battery) {
        return Double.isNaN(battery) ? "n/a" : String.format(java.util.Locale.ROOT, "%.1f", battery);
    }

    private static ChargeTaskDTO vo(ChargeTask task) {
        return new ChargeTaskDTO(task.id(), task.source(), task.status().name(), task.waypointId(),
                Double.isNaN(task.x()) ? null : task.x(), Double.isNaN(task.y()) ? null : task.y(),
                task.yaw(), task.targetBatteryPct(),
                Double.isNaN(task.batteryAtStart()) ? null : task.batteryAtStart(),
                Double.isNaN(task.batteryNow()) ? null : task.batteryNow(),
                task.message(), task.internalMoveTaskId(), task.createdAt(), task.updatedAt(), task.finishedAt());
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
