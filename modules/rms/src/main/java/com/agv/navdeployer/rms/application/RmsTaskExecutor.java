package com.agv.navdeployer.rms.application;

import com.agv.navdeployer.rms.domain.RmsTask;
import com.agv.navdeployer.rms.domain.RmsTaskRegistry;
import com.agv.navdeployer.rms.domain.RmsTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * RMS 任务执行引擎：按任务类型分派到导航/充电/模拟动作/机械臂质检，并轮询内部任务终态。
 * 由 RmsTaskService 在 task/start、task/resume 后异步调用。
 */
@Component
public class RmsTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(RmsTaskExecutor.class);
    private static final long MOVE_POLL_INTERVAL_MS = 500L;

    private final RmsTaskRegistry registry;
    private final com.agv.navdeployer.rms.domain.RmsResultReporter resultReporter;
    private final com.agv.navdeployer.task.application.MoveTaskService moveTaskService;
    private final com.agv.navdeployer.task.application.SmartNavigationService smartNavigation;
    private final com.agv.navdeployer.task.application.ChargeService chargeService;
    private final com.agv.navdeployer.rms.infrastructure.config.RmsProperties props;
    private final RmsTemplateGate templateGate;
    private final RmsArmSequencer armSequencer;
    private final com.agv.navdeployer.map.application.MapMetaService mapMetaService;

    private final java.util.concurrent.ScheduledExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "rms-task-worker");
                thread.setDaemon(true);
                return thread;
            });

    public RmsTaskExecutor(RmsTaskRegistry registry,
                           com.agv.navdeployer.rms.domain.RmsResultReporter resultReporter,
                           com.agv.navdeployer.task.application.MoveTaskService moveTaskService,
                           com.agv.navdeployer.task.application.SmartNavigationService smartNavigation,
                           com.agv.navdeployer.task.application.ChargeService chargeService,
                           com.agv.navdeployer.rms.infrastructure.config.RmsProperties props,
                           RmsTemplateGate templateGate,
                           RmsArmSequencer armSequencer,
                           com.agv.navdeployer.map.application.MapMetaService mapMetaService) {
        this.registry = registry;
        this.resultReporter = resultReporter;
        this.moveTaskService = moveTaskService;
        this.smartNavigation = smartNavigation;
        this.chargeService = chargeService;
        this.props = props;
        this.templateGate = templateGate;
        this.armSequencer = armSequencer;
        this.mapMetaService = mapMetaService;
    }

    boolean armMovementAllowed() {
        return armSequencer.armMovementAllowed();
    }

    String armState() {
        return armSequencer.armState();
    }

    public void execute(String taskId) {
        RmsTask task = registry.find(taskId).orElse(null);
        if (task == null || task.status() != RmsTaskStatus.RUNNING) {
            return;
        }
        if (task.isInspectionAction()) {
            armSequencer.simulateInspection(task,
                    (t, message) -> fail(t, RmsTaskStatus.AGV_NODE_FAILED, message),
                    this::completeTask);
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
            if (templateGate.templateMissing(task)) {
                log.warn("rms task {} failed: template not downloaded (type={}, code={})",
                        task.taskId(), task.taskTemplateType(), task.templateCode());
                fail(task, RmsTaskStatus.FAILED, templateGate.templateMissingMessage(task));
                return;
            }
            executeCharge(task);
            return;
        }
        dispatchMove(task);
    }

    void dispatchMove(RmsTask task) {
        String taskId = task.taskId();
        var destination = resolveDestination(task);
        if (destination == null) {
            fail(task, RmsTaskStatus.AGV_NODE_FAILED, "no destination for task (template=" + task.taskTemplateType()
                    + ", home pose 未配置且 parameters.destination 缺失)");
            return;
        }
        task.setDestination(destination);
        task.setTargetLabel(resolveTargetLabel(destination.x(), destination.y()));
        registry.save(task);
        try {
            // source=rms:{task_id}：任务页据此把同一 RMS 任务的多阶段执行（导航→充电）关联展示
            var moveTask = smartNavigation.createOptimizedMoveTask(taskId,
                    destination.x(), destination.y(), destination.yaw(), "rms:" + taskId);
            task.setInternalMoveTaskId(moveTask.id());
            registry.save(task);
            log.info("rms task {} dispatched internal move task {} (type={}) -> {} ({}, {}, {}rad)",
                    taskId, moveTask.id(), moveTask.taskType(), task.targetLabel(),
                    destination.x(), destination.y(), destination.yaw());
            scheduleMovePoll(taskId, moveTask.id());
        } catch (Exception exception) {
            fail(task, RmsTaskStatus.AGV_NODE_FAILED, exception.getMessage());
        }
    }

    /** 目标坐标反查点位名（≤1.5m 内最近点），无匹配回退坐标文本；任务页/日志展示用 */
    private String resolveTargetLabel(double x, double y) {
        try {
            var published = mapMetaService.findPublished();
            if (published != null) {
                for (var wp : mapMetaService.listWaypoints(published.getId())) {
                    if (Math.hypot(wp.getX() - x, wp.getY() - y) <= 1.5) {
                        return wp.getWaypointCode();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return String.format(java.util.Locale.ROOT, "(%.2f,%.2f)", x, y);
    }

    private void executeCharge(RmsTask task) {
        var destination = task.destination();
        try {
            if (destination != null) {
                task.setTargetLabel(resolveTargetLabel(destination.x(), destination.y()));
                log.info("rms task {} charge with destination {} ignored for navigation: "
                                + "调度负责分段导航，到位判据=在途移动任务已清空", task.taskId(), task.targetLabel());
            } else {
                task.setTargetLabel("原地充电");
            }
            com.agv.navdeployer.task.application.dto.ChargeTaskDTO chargeTask =
                    chargeService.chargeInPlace("rms:" + task.taskId(), targetBatteryOf(task), false, false);
            task.setInternalChargeTaskId(chargeTask.id());
            registry.save(task);
            log.info("rms task {} dispatched charge task {} (in-place, target={}%)",
                    task.taskId(), chargeTask.id(), chargeTask.targetBatteryPct());
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
        com.agv.navdeployer.task.application.dto.ChargeTaskDTO chargeTask = chargeService.get(chargeTaskId);
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

    private com.agv.navdeployer.rms.infrastructure.protocol.dto.command.TaskCommandRequest.Destination resolveDestination(RmsTask task) {
        if (task.isNavigation()) {
            return task.destination();
        }
        if (task.isTemplate("return_home")) {
            com.agv.navdeployer.rms.infrastructure.config.RmsProperties.Pose pose =
                    props.getBehavior().getHomePose();
            return pose == null ? task.destination()
                    : new com.agv.navdeployer.rms.infrastructure.protocol.dto.command.TaskCommandRequest
                            .Destination(pose.getX(), pose.getY(), pose.getYaw());
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
            var moveTask = moveTaskService.get(moveTaskId);
            moveStatus = moveTask.status();
            moveError = moveTask.errorMessage();
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

    boolean dwell(RmsTask task, long ms) {
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

    void completeTask(RmsTask task) {
        RmsTask current = registry.find(task.taskId()).orElse(null);
        if (current == null || current.status() != RmsTaskStatus.RUNNING) {
            return;
        }
        registry.transition(current.taskId(), RmsTaskStatus.COMPLETED);
        resultReporter.report(current);
    }

    void fail(RmsTask task, RmsTaskStatus failureStatus, String message) {
        RmsTaskStatus status = failureStatus == null || !failureStatus.failed()
                ? RmsTaskStatus.FAILED : failureStatus;
        log.warn("rms task {} failed: status={} code={} msg={}",
                task.taskId(), status, status.actionStatusCode(), message);
        task.setMessage(message);
        registry.transition(task.taskId(), status);
        resultReporter.report(registry.find(task.taskId()).orElse(task));
    }
}
