package com.agv.navdeployer.rms.task;

import com.agv.navdeployer.entity.RmsTaskRecord;
import com.agv.navdeployer.mapper.RmsTaskRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public class RmsTaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(RmsTaskRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, RmsTask> tasks = new ConcurrentHashMap<>();

    private final RmsTaskRecordMapper recordMapper;

    public RmsTaskRegistry() {
        this(null);
    }

    @Autowired
    public RmsTaskRegistry(RmsTaskRecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    public Optional<RmsTask> find(String taskId) {
        return taskId == null ? Optional.empty() : Optional.ofNullable(tasks.get(taskId));
    }

    public void put(RmsTask task) {
        tasks.put(task.taskId(), task);
        persist(task, true);
    }

    public RmsTask remove(String taskId) {
        return taskId == null ? null : tasks.remove(taskId);
    }

    public RmsTask activeTask() {
        return tasks.values().stream()
                .filter(task -> task.status() == RmsTaskStatus.RUNNING || task.status() == RmsTaskStatus.PAUSED)
                .findFirst()
                .orElse(null);
    }

    public boolean busyExcept(String taskId) {
        RmsTask active = activeTask();
        return active != null && !active.taskId().equals(taskId);
    }

    public java.util.List<RmsTask> all() {
        return tasks.values().stream()
                .sorted(java.util.Comparator.comparing(RmsTask::createdAt).reversed())
                .toList();
    }

    public void transition(String taskId, RmsTaskStatus next) {
        find(taskId).ifPresent(task -> {
            RmsTaskStatus previous = task.status();
            task.setStatus(next);
            if (previous != next) {
                log.info("rms task {} -> {} (task_id={})", previous, next, taskId);
            }
            persist(task, false);
        });
    }

    public void save(RmsTask task) {
        persist(task, false);
    }

    public java.util.List<RmsTaskRecord> history(int limit) {
        if (recordMapper == null) {
            return java.util.List.of();
        }
        int bounded = Math.max(1, Math.min(limit, 500));
        return recordMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query
                .QueryWrapper<RmsTaskRecord>().orderByDesc("created_at").last("LIMIT " + bounded));
    }

    public void ifPresent(String taskId, Consumer<RmsTask> consumer) {
        find(taskId).ifPresent(consumer);
    }

    private void persist(RmsTask task, boolean insert) {
        if (recordMapper == null) {
            return;
        }
        try {
            RmsTaskRecord record = toRecord(task);
            if (insert) {
                // RMS 复用同一 task_id 重派单：先删后插防主键冲突，勿改成裸 insert
                recordMapper.deleteById(record.getTaskId());
                recordMapper.insert(record);
            } else {
                recordMapper.updateById(record);
            }
        } catch (Exception exception) {
            log.warn("rms task {} persist history failed: {}", task.taskId(), exception.getMessage());
        }
    }

    static RmsTaskRecord toRecord(RmsTask task) {
        RmsTaskRecord record = new RmsTaskRecord();
        record.setTaskId(task.taskId());
        record.setActionId(task.actionId());
        record.setTaskType(task.taskType());
        record.setTaskTemplateType(task.taskTemplateType());
        record.setTemplateCode(task.templateCode());
        record.setTemplateId(task.templateId());
        record.setParameters(toJson(task.parameters()));
        record.setStatus(task.status().name());
        record.setMessage(task.message());
        if (task.destination() != null) {
            record.setDestX(task.destination().x());
            record.setDestY(task.destination().y());
            record.setDestYaw(task.destination().yaw());
        }
        record.setDestTopoName(topoNameOf(task.parameters()));
        record.setInternalMoveTaskId(task.internalMoveTaskId());
        record.setInternalChargeTaskId(task.internalChargeTaskId());
        record.setCreatedAt(local(task.createdAt()));
        record.setStartedAt(task.startedAt() == null ? null : local(task.startedAt()));
        record.setFinishedAt(task.finishedAt() == null ? null : local(task.finishedAt()));
        return record;
    }

    private static String toJson(Map<String, Object> parameters) {
        if (parameters == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(parameters);
        } catch (Exception exception) {
            return String.valueOf(parameters);
        }
    }

    private static String topoNameOf(Map<String, Object> parameters) {
        if (parameters != null && parameters.get("destination") instanceof Map<?, ?> destination) {
            Object topo = destination.get("topo_name");
            if (topo != null) {
                return String.valueOf(topo);
            }
        }
        return null;
    }

    private static LocalDateTime local(java.time.Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
