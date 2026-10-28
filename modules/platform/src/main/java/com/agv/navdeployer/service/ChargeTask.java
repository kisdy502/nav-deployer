package com.agv.navdeployer.service;

import com.agv.navdeployer.vo.ChargeTaskVO;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 充电任务运行时（domain，内存态；单机器人同时只有一个充电任务，由 ChargeService 维护）。
 * 状态迁移只经 ChargeService（start/cancel/worker 轮询），非终态字段仅 charge-worker 线程写。
 */
final class ChargeTask {

    enum Status {
        MOVING, STARTING, CHARGING, SUCCEEDED, FAILED, CANCELLED;

        boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }
    }

    private final long id;
    private final String source;
    private final Long waypointId;
    private final double x;
    private final double y;
    private final double yaw;
    private final double targetBatteryPct;
    private final LocalDateTime createdAt = LocalDateTime.now();
    /** 创建时刻（epoch ms），全流程看门狗基准 */
    private final long createdAtMs = System.currentTimeMillis();

    private final AtomicReference<Status> status = new AtomicReference<>(Status.MOVING);
    private volatile String message;
    private volatile Long internalMoveTaskId;
    private volatile double batteryAtStart = Double.NaN;
    private volatile double batteryNow = Double.NaN;
    /** set_charging(data=true) 已发出 / 已收到 success 确认的时刻（epoch ms，0=未发生） */
    private volatile long serviceSentAt;
    private volatile long serviceAckAt;
    /** 导航到桩完成的时刻（epoch ms，0=未到桩）：停稳等待的起点 */
    private volatile long dockedAt;
    /** 充电中首次观察到 charging=false 的时刻（宽限计时用，0=无） */
    private volatile long firstChargeLostAt;
    private volatile LocalDateTime updatedAt = createdAt;
    private volatile LocalDateTime finishedAt;

    /** 路线导航完整链（仅 charge-worker 线程访问）：待执行 path 队列 + 段进度 */
    private final java.util.ArrayDeque<Long> remainingPathIds = new java.util.ArrayDeque<>();
    private int moveSegmentsPlanned = 1;
    private int moveSegmentsDone = 0;

    /** SSE 发布去重基线（仅 charge-worker 线程访问） */
    Status lastPublishedStatus;
    double lastPublishedBattery = Double.NaN;
    long lastPublishedAt;

    ChargeTask(long id, String source, Long waypointId,
               double x, double y, double yaw, double targetBatteryPct) {
        this.id = id;
        this.source = source;
        this.waypointId = waypointId;
        this.x = x;
        this.y = y;
        this.yaw = yaw;
        this.targetBatteryPct = targetBatteryPct;
    }

    long id() {
        return id;
    }

    String source() {
        return source;
    }

    Status status() {
        return status.get();
    }

    /** 仅允许从非终态迁移；已终态时拒绝（防 cancel 与 worker 竞态双写）。
     *  @return true=本次迁移生效 */
    boolean transitionTo(Status next) {
        Status current = status.get();
        while (!current.terminal()) {
            if (status.compareAndSet(current, next)) {
                return true;
            }
            current = status.get();
        }
        return false;
    }

    String message() {
        return message;
    }

    void setMessage(String message) {
        this.message = message;
    }

    double targetBatteryPct() {
        return targetBatteryPct;
    }

    Long internalMoveTaskId() {
        return internalMoveTaskId;
    }

    void setInternalMoveTaskId(Long internalMoveTaskId) {
        this.internalMoveTaskId = internalMoveTaskId;
    }

    double batteryAtStart() {
        return batteryAtStart;
    }

    void setBatteryAtStart(double batteryAtStart) {
        this.batteryAtStart = batteryAtStart;
    }

    double batteryNow() {
        return batteryNow;
    }

    void setBatteryNow(double batteryNow) {
        this.batteryNow = batteryNow;
    }

    long serviceSentAt() {
        return serviceSentAt;
    }

    void setServiceSentAt(long serviceSentAt) {
        this.serviceSentAt = serviceSentAt;
    }

    long serviceAckAt() {
        return serviceAckAt;
    }

    void setServiceAckAt(long serviceAckAt) {
        this.serviceAckAt = serviceAckAt;
    }

    long dockedAt() {
        return dockedAt;
    }

    void setDockedAt(long dockedAt) {
        this.dockedAt = dockedAt;
    }

    long firstChargeLostAt() {
        return firstChargeLostAt;
    }

    void setFirstChargeLostAt(long firstChargeLostAt) {
        this.firstChargeLostAt = firstChargeLostAt;
    }

    LocalDateTime createdAt() {
        return createdAt;
    }

    long createdAtMs() {
        return createdAtMs;
    }

    LocalDateTime updatedAt() {
        return updatedAt;
    }

    /** 待执行路线段入队（首个由 start 下发，其余由 MOVING 轮询逐段接力）。 */
    void offerRemainingPaths(java.util.List<Long> pathIds) {
        for (int i = 1; i < pathIds.size(); i++) {
            remainingPathIds.addLast(pathIds.get(i));
        }
        moveSegmentsPlanned = Math.max(1, pathIds.size());
    }

    /** 当前内部移动任务结束后取下一段；无下一段返回 null（整条链走完）。 */
    Long pollNextPathId() {
        moveSegmentsDone++;
        return remainingPathIds.pollFirst();
    }

    /** 路线导航进度描述（"路段 2/3"），非路线导航返回 null。 */
    String segmentProgress() {
        if (moveSegmentsPlanned <= 1) {
            return null;
        }
        return "路段 " + Math.min(moveSegmentsDone + 1, moveSegmentsPlanned) + "/" + moveSegmentsPlanned;
    }

    LocalDateTime finishedAt() {
        return finishedAt;
    }

    /** 迁移到终态时补 finishedAt；任何状态变化都刷 updatedAt。 */
    void stamp(Status next) {
        this.updatedAt = LocalDateTime.now();
        if (next != null && next.terminal()) {
            this.finishedAt = updatedAt;
        }
    }

    ChargeTaskVO vo() {
        return new ChargeTaskVO(id, source, status.get().name(), waypointId,
                Double.isNaN(x) ? null : x, Double.isNaN(y) ? null : y, yaw, targetBatteryPct,
                Double.isNaN(batteryAtStart) ? null : batteryAtStart,
                Double.isNaN(batteryNow) ? null : batteryNow,
                message, internalMoveTaskId, createdAt, updatedAt, finishedAt);
    }
}
