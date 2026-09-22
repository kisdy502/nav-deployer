package com.agv.navdeployer.sim;

import java.time.Instant;

/**
 * 仿真 AGV 遥测数据库（线程安全容器）。
 * 对应 agv_bridge_v2 暴露的 rosbridge 话题：
 * - /agv/status：1Hz，业务状态
 * - /tf：10Hz，map -> <agv_id>/base_link 全局位姿
 * - /scan_1、/scan_2：20Hz，2D 激光
 * - /odom：可选，里程计速度
 */
public final class SimAgvTelemetry {

    /** map 坐标系下的位姿。yaw 为弧度，stamp 为本地接收时间。 */
    public record PoseSnapshot(double x, double y, double yaw, Instant receivedAt) {
    }

    /** /agv/status 业务状态。mode/map_name 为机器人 agv_bridge_v2 v0.3.0+ 字段，旧版本为 null。 */
    public record StatusSnapshot(
            String agvId,
            String state,
            double battery,
            boolean poseInitialized,
            String activeCommandId,
            String activeNodeId,
            String mode,
            String mapName,
            Instant receivedAt
    ) {
    }

    /** 2D 激光一帧摘要：束数 + 有限测距的最小/最大值。 */
    public record ScanSnapshot(
            String frameId,
            int rangeCount,
            double minRange,
            double maxRange,
            Instant receivedAt
    ) {
    }

    public record Snapshot(
            boolean connected,
            StatusSnapshot status,
            PoseSnapshot mapPose,
            PoseSnapshot odomPose,
            double odomLinearX,
            double odomAngularZ,
            long statusCount,
            long tfCount,
            long odomCount,
            long scan1Count,
            long scan2Count,
            Instant lastMessageAt
    ) {
    }

    private volatile boolean connected;
    private volatile StatusSnapshot status;
    private volatile PoseSnapshot mapPose;
    private volatile PoseSnapshot odomPose;
    private volatile ScanSnapshot scan1;
    private volatile ScanSnapshot scan2;
    private volatile double odomLinearX;
    private volatile double odomAngularZ;
    private volatile long statusCount;
    private volatile long tfCount;
    private volatile long odomCount;
    private volatile long scan1Count;
    private volatile long scan2Count;
    private volatile Instant lastMessageAt;

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public StatusSnapshot getStatus() {
        return status;
    }

    public void setStatus(StatusSnapshot status) {
        this.status = status;
        this.statusCount++;
    }

    public PoseSnapshot getMapPose() {
        return mapPose;
    }

    public void setMapPose(PoseSnapshot mapPose) {
        this.mapPose = mapPose;
        this.tfCount++;
    }

    public PoseSnapshot getOdomPose() {
        return odomPose;
    }

    public void setOdomPose(PoseSnapshot odomPose) {
        this.odomPose = odomPose;
        this.odomCount++;
    }

    public ScanSnapshot getScan1() {
        return scan1;
    }

    public void setScan1(ScanSnapshot scan1) {
        this.scan1 = scan1;
        this.scan1Count++;
    }

    public ScanSnapshot getScan2() {
        return scan2;
    }

    public void setScan2(ScanSnapshot scan2) {
        this.scan2 = scan2;
        this.scan2Count++;
    }

    public double getOdomLinearX() {
        return odomLinearX;
    }

    public void setOdomVelocity(double linearX, double angularZ) {
        this.odomLinearX = linearX;
        this.odomAngularZ = angularZ;
    }

    public double getOdomAngularZ() {
        return odomAngularZ;
    }

    public long getStatusCount() {
        return statusCount;
    }

    public long getTfCount() {
        return tfCount;
    }

    public long getOdomCount() {
        return odomCount;
    }

    public long getScan1Count() {
        return scan1Count;
    }

    public long getScan2Count() {
        return scan2Count;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public void touch() {
        this.lastMessageAt = Instant.now();
    }

    /** /agv/status 距上次收到的毫秒数；从未收到返回 -1。 */
    public long statusAgeMs() {
        StatusSnapshot snapshot = status;
        return snapshot == null ? -1L : Math.max(0L, System.currentTimeMillis() - snapshot.receivedAt().toEpochMilli());
    }

    /**
     * 镜像状态是否「新鲜」：已连接 + 已收到过 status + 距上次收到不超过 maxAgeMs。
     * 业务闸门（切图/建图/保存等模式类操作）必须以此为准，不新鲜视为 UNKNOWN。
     */
    public boolean isStatusFresh(long maxAgeMs) {
        return connected && status != null && statusAgeMs() <= maxAgeMs;
    }

    public Snapshot snapshot() {
        return new Snapshot(
                connected,
                status,
                mapPose,
                odomPose,
                odomLinearX,
                odomAngularZ,
                statusCount,
                tfCount,
                odomCount,
                scan1Count,
                scan2Count,
                lastMessageAt
        );
    }
}
