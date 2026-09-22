package com.agv.navdeployer.service;

import java.time.Instant;
import java.util.Optional;

/**
 * 地图模式任务状态机（纯逻辑、无副作用，便于单测）。
 *
 * 驱动方式：由服务层轮询 /agv/status 镜像，喂入 mode / map_name / 状态年龄；
 * 机器人是唯一事实源 —— 各服务的响应只代表「已受理」，终态只认 mode 流转。
 *
 * SWITCH_MAP（/agv/load_map）：
 *   DISPATCHED --mode=RELOCALIZING--> RELOCATING --mode=NAVIGATION 且 map_name=目标--> SUCCEEDED
 *   异常：mode=MAPPING（被建图抢占）/ RELOCIZING 后回到 NAVIGATION 但不是目标 / 失联 / 超时
 *
 * START_MAPPING（/agv/start_mapping）：
 *   DISPATCHED --mode=MAPPING--> SUCCEEDED（map_name 被机器人清空）
 *   异常：mode=RELOCALIZING（被切图抢占）/ 失联 / 超时
 *
 * SAVE_MAP（/agv/save_map）：
 *   DISPATCHED（保存中，mode 维持 MAPPING）--mode=RELOCALIZING--> RELOCATING
 *   --mode=NAVIGATION 且 map_name=目标--> SUCCEEDED（成功后服务层自动同步栅格入库）
 *   异常：RELOCIZING 后回到 NAVIGATION 但不是目标 / 失联 / 超时（保存/转换失败时机器人维持 MAPPING，只能超时兜底）
 */
public final class MapModeStateMachine {

    public enum Type {
        /** 切图（/agv/load_map）。 */
        SWITCH_MAP,
        /** 进入建图（/agv/start_mapping）。 */
        START_MAPPING,
        /** 保存建图并回定位（/agv/save_map）。 */
        SAVE_MAP
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

    /** 喂入一帧 status 快照；返回状态迁移结果（未变化返回 empty，终态只迁移一次）。 */
    public synchronized Optional<Status> onStatus(String mode, String mapName, long statusAgeMs, long staleLimitMs) {
        if (isTerminal()) {
            return Optional.empty();
        }
        if (statusAgeMs > staleLimitMs) {
            return transition(Status.FAILED, "机器人状态失联（" + statusAgeMs / 1000 + "s 未收到 /agv/status）");
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

    /** SWITCH_MAP 与 SAVE_MAP 共用：目标都是 NAVIGATION 且 map_name=目标。 */
    private Optional<Status> onStatusForMapSwitchLike(String mode, String mapName) {
        switch (mode) {
            case "RELOCALIZING" -> {
                return transition(Status.RELOCATING, null);
            }
            case "MAPPING" -> {
                if (type == Type.SWITCH_MAP) {
                    return transition(Status.FAILED, "机器人进入建图模式（MAPPING），切图被抢占");
                }
                // SAVE_MAP：保存/转换执行中，mode 维持 MAPPING 是正常过程
                return Optional.empty();
            }
            case "NAVIGATION" -> {
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

    /** 整体截止时间到；返回状态迁移结果。 */
    public synchronized Optional<Status> onDeadline(Instant now) {
        if (isTerminal() || now.isBefore(deadlineAt)) {
            return Optional.empty();
        }
        return transition(Status.FAILED, type + " 超时（mode 未在期限内达到预期）");
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

    public synchronized Instant getDeadlineAt() {
        return deadlineAt;
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
