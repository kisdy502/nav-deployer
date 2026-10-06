package com.agv.navdeployer.rms.task;

import com.agv.navdeployer.dto.MoveTaskCreateDTO;
import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.protocol.dto.command.BodyReply;
import com.agv.navdeployer.rms.protocol.dto.command.TaskCommandRequest;
import com.agv.navdeployer.rms.state.RobotStateView;
import com.agv.navdeployer.service.MoveTaskService;
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
 * <p>生命周期：accepted →(start) running →(完成/失败) completed/failed → result_report；
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

    /** 执行与轮询线程：与 RMS query 回调线程隔离，保证 queryable 快速回复 */
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "rms-task-worker");
                thread.setDaemon(true);
                return thread;
            });

    public RmsTaskService(RmsTaskRegistry registry,
                          RmsResultReporter resultReporter,
                          RobotStateView stateView,
                          MoveTaskService moveTaskService,
                          RmsProperties props) {
        this.registry = registry;
        this.resultReporter = resultReporter;
        this.stateView = stateView;
        this.moveTaskService = moveTaskService;
        this.props = props;
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
        List<Map<String, String>> templates = List.of(
                template("rmf_navigate", "navigate"),
                template("quality_inspection", "action"),
                template("charge", "action"),
                template("replace_battery", "action"),
                template("return_home", "action"),
                template("pause_task", "action"),
                template("resume_task", "action"));
        return BodyReply.success("success", templates);
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
        if (task.isTemplate("quality_inspection")) {
            simulateInspection(task);
            return;
        }
        // navigate / charge / replace_battery / return_home 统一走移动
        TaskCommandRequest.Destination destination = resolveDestination(task);
        if (destination == null) {
            fail(task, "no destination for task (template=" + task.taskTemplateType()
                    + ", charge/home pose 未配置且 parameters.destination 缺失)");
            return;
        }
        task.setDestination(destination);
        try {
            MoveTaskVO moveTask = moveTaskService.create(new MoveTaskCreateDTO(
                    "GOAL", null, null,
                    destination.x(), destination.y(), destination.yaw(),
                    null, Boolean.TRUE));
            task.setInternalMoveTaskId(moveTask.getId());
            log.info("rms task {} dispatched internal move task {} -> ({}, {}, {}rad)",
                    taskId, moveTask.getId(), destination.x(), destination.y(), destination.yaw());
            scheduleMovePoll(taskId, moveTask.getId());
        } catch (Exception exception) {
            // MoveTaskService 的闸门（连接/定位/模式/占用）在这里拦截
            fail(task, exception.getMessage());
        }
    }

    private TaskCommandRequest.Destination resolveDestination(RmsTask task) {
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
        if (task == null || task.status() != RmsTaskStatus.RUNNING) {
            return;
        }
        String moveStatus;
        try {
            moveStatus = moveTaskService.get(moveTaskId).getStatus();
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
            case "FAILED", "TIMEOUT" -> fail(task, "move task " + moveStatus.toLowerCase());
            default -> scheduleMovePoll(taskId, moveTaskId);
        }
    }

    /** 质检占位执行：固定时长后完成（仿真机械臂接入后替换此方法）。 */
    private void simulateInspection(RmsTask task) {
        long durationMs = Math.max(0L, props.getBehavior().getInspectionDurationMs());
        log.info("rms task {} simulated inspection start, duration={}ms", task.taskId(), durationMs);
        worker.schedule(() -> {
            RmsTask current = registry.find(task.taskId()).orElse(null);
            if (current == null || current.status() != RmsTaskStatus.RUNNING) {
                return;
            }
            registry.transition(current.taskId(), RmsTaskStatus.COMPLETED);
            resultReporter.report(current);
        }, durationMs, TimeUnit.MILLISECONDS);
    }

    private void fail(RmsTask task, String message) {
        log.warn("rms task {} failed: {}", task.taskId(), message);
        registry.transition(task.taskId(), RmsTaskStatus.FAILED);
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

    private static Map<String, String> template(String templateType, String taskType) {
        return Map.of(
                "template_id", templateType,
                "template_type", templateType,
                "task_type", taskType);
    }

}
