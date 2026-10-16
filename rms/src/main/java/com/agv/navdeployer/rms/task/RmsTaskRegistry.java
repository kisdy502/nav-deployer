package com.agv.navdeployer.rms.task;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * RMS 任务注册表：task_id → 运行时。单机器人单任务互斥，
 * 状态迁移统一走 {@link #transition}（含日志）。
 */
@Component
public class RmsTaskRegistry {

    private final Map<String, RmsTask> tasks = new ConcurrentHashMap<>();

    public Optional<RmsTask> find(String taskId) {
        return taskId == null ? Optional.empty() : Optional.ofNullable(tasks.get(taskId));
    }

    public void put(RmsTask task) {
        tasks.put(task.taskId(), task);
    }

    public RmsTask remove(String taskId) {
        return taskId == null ? null : tasks.remove(taskId);
    }

    /** 唯一在途任务（RUNNING / PAUSED），无则 null。 */
    public RmsTask activeTask() {
        return tasks.values().stream()
                .filter(task -> task.status() == RmsTaskStatus.RUNNING || task.status() == RmsTaskStatus.PAUSED)
                .findFirst()
                .orElse(null);
    }

    /** 是否有在途任务（不含指定的这个）。 */
    public boolean busyExcept(String taskId) {
        RmsTask active = activeTask();
        return active != null && !active.taskId().equals(taskId);
    }

    public void transition(String taskId, RmsTaskStatus next) {
        find(taskId).ifPresent(task -> {
            RmsTaskStatus previous = task.status();
            task.setStatus(next);
            if (previous != next) {
                org.slf4j.LoggerFactory.getLogger(RmsTaskRegistry.class)
                        .info("rms task {} -> {} (task_id={})", previous, next, taskId);
            }
        });
    }

    public void ifPresent(String taskId, Consumer<RmsTask> consumer) {
        find(taskId).ifPresent(consumer);
    }
}
