package com.agv.navdeployer.service;

import com.agv.navdeployer.sim.LocalizationOperation;

import java.time.Instant;
import java.util.Optional;

public final class MapModeStateMachine {

    public enum Type {
        SWITCH_MAP,
        START_MAPPING,
        SAVE_MAP,
        DISCARD_MAPPING,
        RELOCALIZE
    }

    public enum Status {
        DISPATCHED,
        RELOCATING,
        SUCCEEDED,
        FAILED
    }

    private final long id;
    private final Type type;
    private final Long navMapId;
    private final Long v2MapId;
    private final String mapName;
    private final String robotMapName;
    private final Instant deadlineAt;
    private volatile Instant deadlineExtendedTo;

    private String operationId;
    private String stage;
    private String detailMessage;
    private int progressVersion;

    private Status status;
    private String errorMessage;
    private final Instant createdAt = Instant.now();
    private Instant updatedAt = createdAt;
    private Instant finishedAt;

    public MapModeStateMachine(long id, Type type, Long navMapId,
                               String mapName, String robotMapName, Instant deadlineAt) {
        this(id, type, navMapId, null, mapName, robotMapName, deadlineAt);
    }

    public MapModeStateMachine(long id, Type type, Long navMapId, Long v2MapId,
                               String mapName, String robotMapName, Instant deadlineAt) {
        this.id = id;
        this.type = type;
        this.navMapId = navMapId;
        this.v2MapId = v2MapId;
        this.mapName = mapName;
        this.robotMapName = robotMapName;
        this.deadlineAt = deadlineAt;
        this.status = Status.DISPATCHED;
    }

    public synchronized void attachOperation(String operationId, String stage, String message) {
        if (operationId != null && !operationId.isBlank()) {
            this.operationId = operationId;
        }
        updateProgress(stage, message);
    }

    public synchronized boolean hasOperation() {
        return operationId != null && !operationId.isBlank();
    }

    public synchronized Optional<Status> onOperation(LocalizationOperation operation) {
        if (isTerminal() || operation == null) {
            return Optional.empty();
        }
        if (operationId == null || !operationId.equals(operation.operationId())) {
            return Optional.empty();
        }
        if (operation.isTerminal()) {
            if (LocalizationOperation.STATE_SUCCEEDED.equals(operation.state())) {
                return transition(Status.SUCCEEDED, null);
            }
            return transition(Status.FAILED, operation.failureReason());
        }
        updateProgress(operation.stage(), operation.message());
        if (status == Status.DISPATCHED) {
            return transition(Status.RELOCATING, null);
        }
        return Optional.empty();
    }

    private void updateProgress(String newStage, String newMessage) {
        boolean stageChanged = newStage != null && !newStage.isBlank() && !newStage.equals(stage);
        boolean messageChanged = newMessage != null && !newMessage.isBlank() && !newMessage.equals(detailMessage);
        if (!stageChanged && !messageChanged) {
            return;
        }
        if (stageChanged) {
            this.stage = newStage;
        }
        if (messageChanged) {
            this.detailMessage = newMessage;
        }
        this.progressVersion++;
        this.updatedAt = Instant.now();
    }

    public synchronized Optional<Status> onStatus(String mode, String mapName, long statusAgeMs, long staleLimitMs) {
        if (isTerminal()) {
            return Optional.empty();
        }
        if (statusAgeMs > staleLimitMs) {
            return Optional.empty();
        }
        if (mode == null) {
            // 旧版机器人不携带 mode 字段：无法跟踪，交给超时兜底
            return Optional.empty();
        }
        if (Status.DISPATCHED == status || Status.RELOCATING == status) {
            if (type == Type.START_MAPPING) {
                return onStatusForStartMapping(mode);
            }
            return onStatusForMapSwitchLike(mode, mapName);
        }
        return Optional.empty();
    }

    private Optional<Status> onStatusForStartMapping(String mode) {
        switch (mode) {
            case "MAPPING" -> {
                return transition(Status.SUCCEEDED, null);
            }
            case "RELOCALIZING" -> {
                return transition(Status.FAILED, "机器人进入切图流程（RELOCALIZING），建图被抢占");
            }
            default -> {
                // NAVIGATION：受理后 mode 尚未翻转的正常窗口，交给超时兜底
                return Optional.empty();
            }
        }
    }

    private Optional<Status> onStatusForMapSwitchLike(String mode, String mapName) {
        switch (mode) {
            case "RELOCALIZING" -> {
                return transition(Status.RELOCATING, null);
            }
            case "MAPPING" -> {
                if (type == Type.SWITCH_MAP) {
                    return transition(Status.FAILED, "机器人进入建图模式（MAPPING），切图被抢占");
                }
                if (type == Type.RELOCALIZE) {
                    return transition(Status.FAILED, "机器人进入建图模式（MAPPING），重定位被抢占");
                }
                // SAVE_MAP：保存/转换执行中，mode 维持 MAPPING 是正常过程
                // DISCARD_MAPPING：load_map 终止建图轨迹执行中，同样维持 MAPPING
                return Optional.empty();
            }
            case "NAVIGATION" -> {
                if (hasOperation()) {
                    // 新版机器人：成功结论只来自操作记录；mode 回 NAVIGATION 仅是旁证
                    return Optional.empty();
                }
                if (type == Type.RELOCALIZE) {
                    // 旧版机器人 fallback：必须先见过 RELOCALIZING 再回 NAVIGATION 才算收敛，
                    // 否则受理后 mode 未翻转的正常窗口会被误判成功
                    if (Status.RELOCATING == status) {
                        return transition(Status.SUCCEEDED, null);
                    }
                    return Optional.empty();
                }
                if (robotMapName.equals(mapName)) {
                    return transition(Status.SUCCEEDED, null);
                }
                if (Status.RELOCATING == status) {
                    return transition(Status.FAILED,
                            "定位已收敛但当前地图为 " + mapName + "，不是目标 " + robotMapName);
                }
                // DISPATCHED 阶段维持旧地图 NAVIGATION 是正常窗口（受理后 mode 尚未翻转）
                return Optional.empty();
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    public synchronized Optional<Status> onDeadline(Instant now) {
        if (isTerminal() || now.isBefore(getDeadlineAt())) {
            return Optional.empty();
        }
        return transition(Status.FAILED, type + " 超时（mode 未在期限内达到预期）");
    }

    public synchronized void extendDeadlineTo(Instant newDeadline) {
        if (newDeadline != null && newDeadline.isAfter(deadlineAt)) {
            this.deadlineExtendedTo = newDeadline;
        }
    }

    public synchronized void fail(String reason) {
        if (!isTerminal()) {
            transition(Status.FAILED, reason);
        }
    }

    public synchronized boolean isTerminal() {
        return status == Status.SUCCEEDED || status == Status.FAILED;
    }

    private Optional<Status> transition(Status next, String reason) {
        if (next == status) {
            return Optional.empty();
        }
        this.status = next;
        this.errorMessage = reason;
        this.updatedAt = Instant.now();
        if (isTerminal()) {
            this.finishedAt = Instant.now();
        }
        return Optional.of(next);
    }

    public synchronized long getId() {
        return id;
    }

    public synchronized Type getType() {
        return type;
    }

    public synchronized Status getStatus() {
        return status;
    }

    public synchronized Long getNavMapId() {
        return navMapId;
    }

    public synchronized Long getV2MapId() {
        return v2MapId;
    }

    public synchronized String getMapName() {
        return mapName;
    }

    public synchronized String getRobotMapName() {
        return robotMapName;
    }

    public synchronized String getErrorMessage() {
        return errorMessage;
    }

    public synchronized String getOperationId() {
        return operationId;
    }

    public synchronized String getStage() {
        return stage;
    }

    public synchronized String getDetailMessage() {
        return detailMessage;
    }

    public synchronized int getProgressVersion() {
        return progressVersion;
    }

    public synchronized Instant getDeadlineAt() {
        Instant extended = deadlineExtendedTo;
        return extended != null && extended.isAfter(deadlineAt) ? extended : deadlineAt;
    }

    public synchronized Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized Instant getUpdatedAt() {
        return updatedAt;
    }

    public synchronized Instant getFinishedAt() {
        return finishedAt;
    }
}
