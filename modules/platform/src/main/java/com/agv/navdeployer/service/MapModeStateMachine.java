package com.agv.navdeployer.service;

import com.agv.navdeployer.sim.LocalizationOperation;

import java.time.Instant;
import java.util.Optional;

/**
 * 地图模式任务状态机（纯逻辑、无副作用，便于单测）。
 *
 * 驱动方式（双通道，由服务层 500ms 轮询喂入）：
 * 1. /agv/status 镜像的 mode / map_name 流转 —— 唯一事实源的传统判据；
 * 2. /agv/localization_operation 定位操作记录 —— 新版机器人的权威终态
 *    （load_map / relocalize 受理后携带 operation_id，终态只有 SUCCEEDED /
 *    FAILED / TIMED_OUT 三种，迟到回调不改写已终态结论）。
 *
 * 优先级：带 operationId 的任务（SWITCH_MAP / DISCARD_MAPPING / RELOCALIZE）以
 * 操作记录的 finished 为权威终态；mode 流转退化为进度展示与旧版机器人 fallback。
 * START_MAPPING / SAVE_MAP 机器人侧不产生操作 ID，仍纯 mode 流转判定。
 *
 * SWITCH_MAP（/agv/load_map）：
 *   DISPATCHED --mode=RELOCALIZING--> RELOCATING --mode=NAVIGATION 且 map_name=目标--> SUCCEEDED
 *   异常：mode=MAPPING（被建图抢占）/ RELOCIZING 后回到 NAVIGATION 但不是目标 / 超时
 *
 * START_MAPPING（/agv/start_mapping）：
 *   DISPATCHED --mode=MAPPING--> SUCCEEDED（map_name 被机器人清空）
 *   异常：mode=RELOCALIZING（被切图抢占）/ 超时
 *
 * SAVE_MAP（/agv/save_map）：
 *   DISPATCHED（保存中，mode 维持 MAPPING）--mode=RELOCALIZING--> RELOCATING
 *   --mode=NAVIGATION 且 map_name=目标--> SUCCEEDED（成功后服务层自动同步栅格入库）
 *   异常：RELOCIZING 后回到 NAVIGATION 但不是目标 / 超时（保存/转换失败时机器人维持 MAPPING，只能超时兜底）
 *
 * DISCARD_MAPPING（放弃建图，/agv/load_map 回建图前地图）：
 *   与 SAVE_MAP 同构——load_map 终止建图轨迹并回载旧图，mode 流转一致；
 *   成功即回到建图前地图的定位（不落盘、不同步栅格、DB ACTIVE 回旧图）。
 *   异常：RELOCIZING 后回到 NAVIGATION 但不是旧图 / 超时（机器人侧不支持 MAPPING 下
 *   load_map 时受理即失败，在服务层抛出）
 *
 * RELOCALIZE（/agv/relocalize，地图内重定位）：
 *   带 operationId：DISPATCHED --操作 RUNNING--> RELOCATING --操作终态--> 权威结论
 *   旧版机器人 fallback（无 operationId）：见过 mode=RELOCALIZING 后回 NAVIGATION
 *   视为成功（近似推断；新机器人绝不用 mode 判定 RELOCALIZE 成功）
 *
 * 状态失联（/agv/status 断流）不判死：保存地图 → 重启定位的窗口里链路可能短暂中断
 * （rosbridge 重连、CPU 高负载等），机器人侧流程往往仍在推进。失联期间挂起不迁移，
 * 恢复后继续跟踪；真失败由任务 deadline（modeTaskTimeoutMs）兜底。
 */
public final class MapModeStateMachine {

    public enum Type {
        /** 切图（/agv/load_map）。 */
        SWITCH_MAP,
        /** 进入建图（/agv/start_mapping）。 */
        START_MAPPING,
        /** 保存建图并回定位（/agv/save_map）。 */
        SAVE_MAP,
        /** 放弃建图并回建图前地图定位（/agv/load_map 旧图，不落盘）。 */
        DISCARD_MAPPING,
        /** 地图内重定位（/agv/relocalize，不换图）。 */
        RELOCALIZE
    }

    public enum Status {
        /** 已受理（load_map 返回 success=true）。 */
        DISPATCHED,
        /** 观察到 mode=RELOCALIZING，等待收敛。 */
        RELOCATING,
        SUCCEEDED,
        FAILED
    }

    private final long id;
    private final Type type;
    private final Long navMapId;
    private final String mapName;
    private final String robotMapName;
    private final Instant deadlineAt;
    /** 兜底截止可延长（带 operation_id 的任务以机器人侧超时结论为先）。 */
    private volatile Instant deadlineExtendedTo;

    /** 机器人侧定位操作 ID（新版 load_map/relocalize 受理响应携带；旧版机器人为 null）。 */
    private String operationId;
    /** 机器人侧操作阶段（STOPPING_LOCALIZATION 等），随 RUNNING 心跳刷新，终态后保留最后阶段。 */
    private String stage;
    /** 机器人侧进度/结论描述（operation.message），展示用，不做分支逻辑。 */
    private String detailMessage;
    /** 进度版本号：stage/message 更新时递增，服务层据此推送 SSE（状态迁移另有通知）。 */
    private int progressVersion;

    private Status status;
    private String errorMessage;
    private final Instant createdAt = Instant.now();
    private Instant updatedAt = createdAt;
    private Instant finishedAt;

    public MapModeStateMachine(long id, Type type, Long navMapId,
                               String mapName, String robotMapName, Instant deadlineAt) {
        this.id = id;
        this.type = type;
        this.navMapId = navMapId;
        this.mapName = mapName;
        this.robotMapName = robotMapName;
        this.deadlineAt = deadlineAt;
        this.status = Status.DISPATCHED;
    }

    /** 服务受理成功后关联机器人操作 ID（须在 registerAndPublish 之前调用）。 */
    public synchronized void attachOperation(String operationId, String stage, String message) {
        if (operationId != null && !operationId.isBlank()) {
            this.operationId = operationId;
        }
        updateProgress(stage, message);
    }

    public synchronized boolean hasOperation() {
        return operationId != null && !operationId.isBlank();
    }

    /**
     * 喂入一条机器人定位操作记录（权威通道）。
     * 终态：SUCCEEDED → SUCCEEDED；FAILED / TIMED_OUT → FAILED（机器人原始原因）。
     * RUNNING：只刷新阶段进度（并迁移到 RELOCATING 表达执行中），不下结论。
     */
    public synchronized Optional<Status> onOperation(LocalizationOperation operation) {
        if (isTerminal() || operation == null) {
            return Optional.empty();
        }
        if (operationId == null || !operationId.equals(operation.operationId())) {
            return Optional.empty(); // 未关联或其他操作的记录，不消费
        }
        if (operation.isTerminal()) {
            if (LocalizationOperation.STATE_SUCCEEDED.equals(operation.state())) {
                return transition(Status.SUCCEEDED, null);
            }
            return transition(Status.FAILED, operation.failureReason());
        }
        // RUNNING：执行中（operation 心跳约 1s 一次，mode 流转可能滞后或缺失）
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

    /** 喂入一帧 status 快照；返回状态迁移结果（未变化返回 empty，终态只迁移一次）。 */
    public synchronized Optional<Status> onStatus(String mode, String mapName, long statusAgeMs, long staleLimitMs) {
        if (isTerminal()) {
            return Optional.empty();
        }
        if (statusAgeMs > staleLimitMs) {
            // 失联只挂起不判死（见类注释）：不迁移、不喂入过期快照，deadline 兜底
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

    /**
     * SWITCH_MAP / SAVE_MAP / DISCARD_MAPPING / RELOCALIZE 共用 mode 流转处理。
     *
     * 带 operationId（新版机器人）：mode 流转只用于进度（RELOCATING）与抢占类失败，
     * 成功结论一律等定位操作记录（权威通道）——文档明确“mode 变为 NAVIGATION、
     * map_name 更新不单独证明加载成功”。
     * 无 operationId（旧版机器人）：维持原 mode 流转终态判定。
     */
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
                    // 见过 RELOCIZING 后回到 NAVIGATION，但加载的不是目标地图
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

    /** 整体截止时间到（按有效兜底截止时间判定，含 operation 延长）；返回状态迁移结果。 */
    public synchronized Optional<Status> onDeadline(Instant now) {
        if (isTerminal() || now.isBefore(getDeadlineAt())) {
            return Optional.empty();
        }
        return transition(Status.FAILED, type + " 超时（mode 未在期限内达到预期）");
    }

    /** 延长兜底截止时间（只延长不缩短）：带 operation_id 的任务等机器人侧超时结论，上位机兜底必须更晚。 */
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

    /** 进度版本号：stage / detailMessage 变化时递增，服务层据此判定是否需要推送 SSE 进度。 */
    public synchronized int getProgressVersion() {
        return progressVersion;
    }

    /** 有效兜底截止时间（原 deadline 与延长值的较大者）。 */
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
