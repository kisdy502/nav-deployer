package com.agv.navdeployer.robot.domain;

import java.time.Instant;

public final class SimAgvTelemetry {

    public record PoseSnapshot(double x, double y, double yaw, double stampSec, Instant receivedAt) {
    }

    public record StatusSnapshot(
            String agvId,
            String state,
            double battery,
            boolean poseInitialized,
            String activeCommandId,
            String activeNodeId,
            String mode,
            String mapName,
            String batteryState,
            Boolean charging,
            Boolean chargeConnected,
            Boolean batteryDataValid,
            Instant receivedAt
    ) {
    }

    public record ScanSnapshot(
            String frameId,
            int rangeCount,
            double minRange,
            double maxRange,
            double[] points,
            PoseSnapshot pose,
            Instant receivedAt
    ) {
    }

    public record JointSnapshot(String[] names, double[] positions, Instant receivedAt) {
    }

    public record TfFrameSnapshot(String parent, String child,
                                  double x, double y, double z,
                                  double qx, double qy, double qz, double qw) {
    }

    public record Snapshot(
            boolean connected,
            StatusSnapshot status,
            PoseSnapshot mapPose,
            PoseSnapshot odomPose,
            double odomLinearX,
            double odomLinearY,
            double odomAngularZ,
            JointSnapshot joints,
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
    private volatile String topoName;
    private volatile PoseSnapshot odomPose;
    private volatile ScanSnapshot scan1;
    private volatile ScanSnapshot scan2;
    private volatile JointSnapshot jointStates;
    private final java.util.concurrent.ConcurrentHashMap<String, TfFrameSnapshot> tfFrames =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile double odomLinearX;
    private volatile double odomLinearY;
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

    public String getTopoName() {
        return topoName;
    }

    public void setTopoName(String topoName) {
        this.topoName = topoName;
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

    public double getOdomLinearY() {
        return odomLinearY;
    }

    public void setOdomVelocity(double linearX, double linearY, double angularZ) {
        this.odomLinearX = linearX;
        this.odomLinearY = linearY;
        this.odomAngularZ = angularZ;
    }

    public JointSnapshot getJointStates() {
        return jointStates;
    }

    public void setJointStates(JointSnapshot jointStates) {
        this.jointStates = jointStates;
    }

    public void putTfFrame(TfFrameSnapshot frame) {
        if (tfFrames.size() > 256) {
            tfFrames.clear();
        }
        tfFrames.put(frame.child(), frame);
    }

    public java.util.Collection<TfFrameSnapshot> getTfFrames() {
        return tfFrames.values();
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

    public long statusAgeMs() {
        StatusSnapshot snapshot = status;
        return snapshot == null ? -1L : Math.max(0L, System.currentTimeMillis() - snapshot.receivedAt().toEpochMilli());
    }

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
                odomLinearY,
                odomAngularZ,
                jointStates,
                statusCount,
                tfCount,
                odomCount,
                scan1Count,
                scan2Count,
                lastMessageAt
        );
    }
}
