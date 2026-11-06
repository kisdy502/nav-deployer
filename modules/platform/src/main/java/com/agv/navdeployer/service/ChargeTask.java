package com.agv.navdeployer.service;

import com.agv.navdeployer.vo.ChargeTaskVO;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

final class ChargeTask {

    enum Status {
        MOVING, STARTING, SUCCEEDED, FAILED, CANCELLED;

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
    private final long createdAtMs = System.currentTimeMillis();

    private final AtomicReference<Status> status = new AtomicReference<>(Status.MOVING);
    private volatile String message;
    private volatile Long internalMoveTaskId;
    private volatile double batteryAtStart = Double.NaN;
    private volatile double batteryNow = Double.NaN;
    private volatile long serviceSentAt;
    private volatile long serviceAckAt;
    private volatile long dockedAt;
    private volatile LocalDateTime updatedAt = createdAt;
    private volatile LocalDateTime finishedAt;

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

    double x() {
        return x;
    }

    double y() {
        return y;
    }

    Status status() {
        return status.get();
    }

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

    LocalDateTime createdAt() {
        return createdAt;
    }

    long createdAtMs() {
        return createdAtMs;
    }

    LocalDateTime updatedAt() {
        return updatedAt;
    }

    LocalDateTime finishedAt() {
        return finishedAt;
    }

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
