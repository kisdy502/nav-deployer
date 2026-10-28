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

    /**
     * map 坐标系下的位姿。yaw 为弧度；stampSec 为 ROS 消息时间戳（秒，消息缺 stamp 时为 0），
     * 供雷达帧按"扫描时刻"回溯位姿；receivedAt 为本地接收时间。
     */
    public record PoseSnapshot(double x, double y, double yaw, double stampSec, Instant receivedAt) {
    }

    /**
     * /agv/status 业务状态。mode/map_name 为机器人 agv_bridge_v2 v0.3.0+ 字段，旧版本为 null；
     * battery_state/charging/charge_connected/batteryDataValid 为电量接入（battery_simulator，
     * 后续真机 BMS 同格式）新增字段，旧版本机器人恒为 null（用 Boolean 区分「未携带」与 false）。
     */
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

    /**
     * 2D 激光一帧：束数 + 有限测距的最小/最大值 + 点云坐标 + 捕获时位姿。
     * points 为传感器（base_link）系下的扁平坐标 [x0,y0,x1,y1,...]，单位米，保留 3 位小数；
     * pose 为该帧捕获时刻插值得到的 map 系位姿（点云投影到地图必须用它，而非"当前"位姿），
     * 从未收到位姿时为 null；消息缺 angle_min/angle_increment 时 points 为空数组（仅摘要可用）。
     */
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

    /** /joint_states 关节快照：name 与 position 一一对应（底盘舵轮 + 机械臂关节混在同一消息）。 */
    public record JointSnapshot(String[] names, double[] positions, Instant receivedAt) {
    }

    /** TF 树一帧原始相对变换（parent → child），供前端按真实构型绘制（轮位/臂骨架）。 */
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
    /** 机器人上报的当前所在点位名（/agv/pose topo_name；在点位上时非空，离开后置空）。 */
    private volatile String topoName;
    private volatile PoseSnapshot odomPose;
    private volatile ScanSnapshot scan1;
    private volatile ScanSnapshot scan2;
    private volatile JointSnapshot jointStates;
    /** TF 树最新变换缓存（child frame → 相对 parent 的变换），/tf 与 /tf_static 合并写入。 */
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

    /** 机器人上报的当前所在点位名（/agv/pose topo_name；在点位上时非空，离开后置空）。 */
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

    /** /odom twist（车体系）：全向底盘横移速度藏在 linear.y，合成线速必须带上。 */
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
