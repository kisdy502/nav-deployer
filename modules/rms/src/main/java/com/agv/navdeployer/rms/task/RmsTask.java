package com.agv.navdeployer.rms.task;

import com.agv.navdeployer.rms.protocol.dto.command.TaskCommandRequest;
import com.agv.navdeployer.rms.protocol.dto.command.TaskInfoReport;

import java.time.Instant;
import java.util.Map;

public final class RmsTask {

    private final String taskId;
    private final String actionId;
    private final String taskType;
    private final String taskTemplateType;
    private final String templateCode;
    private final String templateId;
    private final Map<String, Object> parameters;
    private final Instant createdAt = Instant.now();

    private RmsTaskStatus status = RmsTaskStatus.IDLE;
    private Instant startedAt;
    private Instant finishedAt;
    private TaskCommandRequest.Destination destination;
    private Long internalMoveTaskId;
    private Long internalChargeTaskId;
    private String message;

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

    public String parameterText(String key) {
        Object value = parameters == null ? null : parameters.get(key);
        return value == null ? null : String.valueOf(value);
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

    public java.time.Instant createdAt() {
        return createdAt;
    }

    public java.time.Instant startedAt() {
        return startedAt;
    }

    public java.time.Instant finishedAt() {
        return finishedAt;
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

    public Long internalChargeTaskId() {
        return internalChargeTaskId;
    }

    public void setInternalChargeTaskId(Long internalChargeTaskId) {
        this.internalChargeTaskId = internalChargeTaskId;
    }

    public String message() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isChargingBehavior() {
        return status == RmsTaskStatus.RUNNING
                && internalChargeTaskId != null
                && (isTemplate("charge") || isTemplate("replace_battery"));
    }

    public boolean isHomingBehavior() {
        return status == RmsTaskStatus.RUNNING && isTemplate("return_home");
    }

    public boolean isTemplate(String templateType) {
        return taskTemplateType != null && taskTemplateType.equalsIgnoreCase(templateType);
    }

    public boolean isNavigation() {
        return isTemplate("rmf_navigate") || isTemplate("navigate")
                || "navigate".equalsIgnoreCase(taskType);
    }

    public boolean isAction() {
        return "action".equalsIgnoreCase(taskType);
    }

    public boolean isMoveBehavior() {
        return isTemplate("charge") || isTemplate("replace_battery") || isTemplate("return_home");
    }

    public boolean isArmGateExempt() {
        return isAction() && !isMoveBehavior();
    }

    public boolean isInspectionAction() {
        return isTemplate("quality_inspection") && "action".equalsIgnoreCase(taskType);
    }

    public TaskInfoReport toInfoReport() {
        return new TaskInfoReport(
                taskId, actionId, taskType, taskTemplateType, templateCode, templateId,
                status.wireName(), status.actionStatusCode(), parameters);
    }
}
