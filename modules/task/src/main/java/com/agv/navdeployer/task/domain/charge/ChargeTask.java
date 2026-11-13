package com.agv.navdeployer.task.domain.charge;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

public final class ChargeTask {

    public enum Status {
        MOVING, STARTING, CHARGING, PAUSED, SUCCEEDED, FAILED, CANCELLED;

        public boolean terminal() {
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
    private volatile boolean persistCharging;
    private volatile Status pausedFrom;
    private volatile LocalDateTime updatedAt = createdAt;
    private volatile LocalDateTime finishedAt;

    public Status lastPublishedStatus;
    public double lastPublishedBattery = Double.NaN;
    public long lastPublishedAt;

    public ChargeTask(long id, String source, Long waypointId,
               double x, double y, double yaw, double targetBatteryPct) {
        this.id = id;
        this.source = source;
        this.waypointId = waypointId;
        this.x = x;
        this.y = y;
        this.yaw = yaw;
        this.targetBatteryPct = targetBatteryPct;
    }

    public long id() {
        return id;
    }

    public String source() {
        return source;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double yaw() {
        return yaw;
    }

    public Long waypointId() {
        return waypointId;
    }

    public Status status() {
        return status.get();
    }

    public boolean transitionTo(Status next) {
        Status current = status.get();
        while (!current.terminal()) {
            if (status.compareAndSet(current, next)) {
                return true;
            }
            current = status.get();
        }
        return false;
    }

    public String message() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public double targetBatteryPct() {
        return targetBatteryPct;
    }

    public Long internalMoveTaskId() {
        return internalMoveTaskId;
    }

    public void setInternalMoveTaskId(Long internalMoveTaskId) {
        this.internalMoveTaskId = internalMoveTaskId;
    }

    public double batteryAtStart() {
        return batteryAtStart;
    }

    public void setBatteryAtStart(double batteryAtStart) {
        this.batteryAtStart = batteryAtStart;
    }

    public double batteryNow() {
        return batteryNow;
    }

    public void setBatteryNow(double batteryNow) {
        this.batteryNow = batteryNow;
    }

    public long serviceSentAt() {
        return serviceSentAt;
    }

    public void setServiceSentAt(long serviceSentAt) {
        this.serviceSentAt = serviceSentAt;
    }

    public long serviceAckAt() {
        return serviceAckAt;
    }

    public void setServiceAckAt(long serviceAckAt) {
        this.serviceAckAt = serviceAckAt;
    }

    public long dockedAt() {
        return dockedAt;
    }

    public void setDockedAt(long dockedAt) {
        this.dockedAt = dockedAt;
    }

    public boolean persistCharging() {
        return persistCharging;
    }

    public void setPersistCharging(boolean persistCharging) {
        this.persistCharging = persistCharging;
    }

    public Status pausedFrom() {
        return pausedFrom;
    }

    public void setPausedFrom(Status pausedFrom) {
        this.pausedFrom = pausedFrom;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    public LocalDateTime updatedAt() {
        return updatedAt;
    }

    public LocalDateTime finishedAt() {
        return finishedAt;
    }

    public void stamp(Status next) {
        this.updatedAt = LocalDateTime.now();
        if (next != null && next.terminal()) {
            this.finishedAt = updatedAt;
        }
    }
}
