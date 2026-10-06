package com.agv.navdeployer.rms.task;

import com.agv.navdeployer.rms.protocol.dto.command.TaskCommandRequest;
import com.agv.navdeployer.rms.protocol.dto.command.TaskInfoReport;

import java.time.Instant;
import java.util.Map;

/**
 * RMS 任务运行时（domain）。状态迁移只经 {@link RmsTaskRegistry}，
 * 与 mock 的本质区别：携带 destination 快照供 pause→resume 重发，并链接内部 move task。
 */
public final class RmsTask {

    private final String taskId;
    private final String actionId;
    private final String taskType;
    private final String taskTemplateType;
    private final String templateCode;
    private final String templateId;
    private final Map<String, Object> parameters;
    private final Instant createdAt = Instant.now();

    private RmsTaskStatus status = RmsTaskStatus.ACCEPTED;
    private Instant startedAt;
    private Instant finishedAt;
    /** navigate 类目标快照（pause 后 resume 重发用） */
    private TaskCommandRequest.Destination destination;
    /** 内部 MoveTask id（导航执行期间） */
    private Long internalMoveTaskId;

    public RmsTask(TaskCommandRequest request) {
        this.taskId = request.taskId();
        this.actionId = request.effectiveActionId();
        this.taskType = request.taskType();
        this.taskTemplateType = request.taskTemplateType();
        this.templateCode = request.templateCode();
        this.templateId = request.templateId();
        this.parameters = request.parameters();
        this.destination = request.destination();
    }

    public String taskId() {
        return taskId;
    }

    public String actionId() {
        return actionId;
    }

    public String taskType() {
        return taskType;
    }

    public String taskTemplateType() {
        return taskTemplateType;
    }

    public String templateCode() {
        return templateCode;
    }

    public String templateId() {
        return templateId;
    }

    public Map<String, Object> parameters() {
        return parameters;
    }

    public RmsTaskStatus status() {
        return status;
    }

    public void setStatus(RmsTaskStatus status) {
        this.status = status;
        if (status == RmsTaskStatus.RUNNING && startedAt == null) {
            startedAt = Instant.now();
        }
        if (status.terminal()) {
            finishedAt = Instant.now();
        }
    }

    public TaskCommandRequest.Destination destination() {
        return destination;
    }

    public void setDestination(TaskCommandRequest.Destination destination) {
        this.destination = destination;
    }

    public Long internalMoveTaskId() {
        return internalMoveTaskId;
    }

    public void setInternalMoveTaskId(Long internalMoveTaskId) {
        this.internalMoveTaskId = internalMoveTaskId;
    }

    /** charge / replace_battery 执行中 → 状态报文 is_charging=true */
    public boolean isChargingBehavior() {
        return status == RmsTaskStatus.RUNNING
                && (isTemplate("charge") || isTemplate("replace_battery"));
    }

    /** return_home 执行中 → 状态报文 is_homing=true */
    public boolean isHomingBehavior() {
        return status == RmsTaskStatus.RUNNING && isTemplate("return_home");
    }

    public boolean isTemplate(String templateType) {
        return taskTemplateType != null && taskTemplateType.equalsIgnoreCase(templateType);
    }

    /** navigate 语义判定：rmf_navigate / navigate 模板，或 task_type 为 navigate。 */
    public boolean isNavigation() {
        return isTemplate("rmf_navigate") || isTemplate("navigate")
                || "navigate".equalsIgnoreCase(taskType);
    }

    public TaskInfoReport toInfoReport() {
        return new TaskInfoReport(
                taskId, actionId, taskType, taskTemplateType, templateCode, templateId,
                status.wireName(), status.actionStatusCode(), parameters);
    }
}
