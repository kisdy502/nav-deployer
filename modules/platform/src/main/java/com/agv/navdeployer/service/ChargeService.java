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

@Component
public class ChargeService {

    private static final Logger log = LoggerFactory.getLogger(ChargeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long PUBLISH_KEEPALIVE_MS = 30000L;

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
    private final RobotOperationalService operationalService;

    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "charge-worker");
                thread.setDaemon(true);
                return thread;
            });

    private final AtomicLong idSeq = new AtomicLong(System.currentTimeMillis());
    private volatile ChargeTask current;
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

    public synchronized ChargeTaskVO start(ChargeCommand command) {
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

        ChargeTask task = new ChargeTask(idSeq.incrementAndGet(), command.source(),
                waypointId, x, y, yaw, target);
        MoveTaskVO moveTask = smartNavigation.createOptimizedMoveTask(
                "charge-" + task.id(), x, y, yaw, "charge:" + task.id());
        task.setInternalMoveTaskId(moveTask.getId());
        task.setBatteryNow(currentBatteryPct());
        this.current = task;
        log.info("charge task {} start: source={} target=({},{},{}rad) targetBattery={}% moveTask={} (type={})",
                task.id(), command.source(), x, y, yaw, target, moveTask.getId(), moveTask.getTaskType());
        publish(task, true);
        worker.schedule(this::poll, props.getPollIntervalMs(), TimeUnit.MILLISECONDS);
        return task.vo();
    }

    public ChargeTaskVO active() {
        ChargeTask task = current;
        return task != null && !task.status().terminal() ? task.vo() : null;
    }

    public ChargeTaskVO get(long id) {
        ChargeTask task = current;
        return task != null && task.id() == id ? task.vo() : null;
    }

    public synchronized ChargeTaskVO startRescue() {
        ChargeTaskVO task = startDirect("rescue", null);
        log.info("charge task {} RESCUE: 远程开始充电（机器人未导航到桩，低电量死锁救援）", task.id());
        return task;
    }

    public synchronized ChargeTaskVO startDirect(String source, Double targetBatteryPct) {
        ChargeTask existing = current;
        if (existing != null && !existing.status().terminal()) {
            throw new IllegalStateException("已有进行中的充电任务（id=" + existing.id()
                    + "，status=" + existing.status() + "），请先取消");
        }
        if (operationalService != null && operationalService.isDecommissioned()) {
            throw new IllegalStateException("机器人已停用（" + operationalService.getReason() + "），无法发起充电");
        }
        double target = targetBatteryPct != null ? targetBatteryPct : props.getTargetBatteryPct();
        if (target <= 0.0 || target > 100.0) {
            throw new IllegalArgumentException("target_battery_pct 必须在 (0, 100]，当前: " + target);
        }
        ChargeTask task = new ChargeTask(idSeq.incrementAndGet(), source, null,
                Double.NaN, Double.NaN, 0.0, target);
        task.setBatteryNow(currentBatteryPct());
        this.current = task;
        log.info("charge task {} DIRECT start: source={} 跳过导航原地开始充电, targetBattery={}%",
                task.id(), source, target);
        beginStartCharging(task);
        publish(task, true);
        worker.schedule(this::poll, props.getPollIntervalMs(), TimeUnit.MILLISECONDS);
        return task.vo();
    }

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
        } else if (phase == ChargeTask.Status.STARTING) {
            callSetCharging(false);
        }
        publish(task, true);
        recordHistory(task);
        return task.vo();
    }

    public void stopCharging() {
        log.info("stop charging requested: calling {} data=false", props.getService());
        callSetCharging(false);
    }

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

    private void poll() {
        try {
            ChargeTask task = current;
            if (task == null || task.status().terminal()) {
                return;
            }
            if (System.currentTimeMillis() - task.createdAtMs() > props.getMaxDurationMs()) {
                finish(task, ChargeTask.Status.FAILED,
                        "充电任务超时（超过 max_duration_ms=" + props.getMaxDurationMs() + "ms）");
                return;
            }
            switch (task.status()) {
                case MOVING -> pollMoving(task);
                case STARTING -> pollStarting(task);
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
            case "SUCCEEDED" -> beginStartCharging(task);
            case "FAILED", "TIMEOUT" -> finish(task, ChargeTask.Status.FAILED,
                    "导航到充电点失败: " + (moveTask.getErrorMessage() == null
                            || moveTask.getErrorMessage().isBlank()
                            ? status : moveTask.getErrorMessage()));
            case "CANCELLED" -> finish(task, ChargeTask.Status.CANCELLED, "导航到充电点被取消");
            default -> {
            }
        }
    }

    private void beginStartCharging(ChargeTask task) {
        if (!Double.isNaN(task.x()) && !Double.isNaN(task.y())) {
            SimAgvTelemetry.PoseSnapshot pose = telemetry.getMapPose();
            if (pose == null) {
                finish(task, ChargeTask.Status.FAILED,
                        "无法获取机器人当前位姿（/tf 未上报），到桩校验无法执行，已拒绝开始充电");
                return;
            }
            double distance = Math.hypot(pose.x() - task.x(), pose.y() - task.y());
            if (distance > props.getDockArrivalRadius()) {
                finish(task, ChargeTask.Status.FAILED, String.format(java.util.Locale.ROOT,
                        "未到达充电桩：机器人距充电桩 (%.2f, %.2f) %.2fm，超过允许半径 %.2fm，已拒绝开始充电",
                        task.x(), task.y(), distance, props.getDockArrivalRadius()));
                return;
            }
        }
        if (!task.transitionTo(ChargeTask.Status.STARTING)) {
            return;
        }
        task.stamp(ChargeTask.Status.STARTING);
        task.setMessage("已到充电点，等待停车稳定");
        task.setDockedAt(System.currentTimeMillis());
        publish(task, true);
        log.info("charge task {} navigation done (dock verified), waiting robot to settle (min={}ms max={}ms)",
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
            call = dispatcher.callService(props.getService(), args, props.getServiceTimeoutMs());
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
            long elapsed = System.currentTimeMillis() - task.dockedAt();
            boolean quiet = isRobotSettled();
            if ((elapsed >= props.getDockSettleMinMs() && quiet) || elapsed >= props.getDockSettleMaxMs()) {
                sendStartCharging(task);
            }
            return;
        }
        if (task.serviceAckAt() > 0) {
            SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
            if (status != null && Boolean.TRUE.equals(status.charging())) {
                task.setBatteryAtStart(status.battery());
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
