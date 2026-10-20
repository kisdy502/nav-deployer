package com.agv.navdeployer.sim;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 机械臂归位联锁状态机测试：HOME/DEPLOYED/MOVING/UNKNOWN 推导与闸门策略。
 */
class ArmStateMonitorTest {

    /** 归位默认值（角色顺序），与 SimAgvProperties 保持同源——标定变更时测试自动跟随 */
    private static final double[] HOME = new SimAgvProperties().getArmHomeLeft();
    /** HOME 换算到关节名字母序 [elbow, lift, pan, wrist1, wrist2, wrist3] */
    private static final double[] LEFT_HOME_ALPHABETICAL =
            {HOME[2], HOME[1], HOME[0], HOME[3], HOME[4], HOME[5]};

    /** 语义关节名（字母序与运动链顺序不一致：elbow < shoulder_lift < shoulder_pan）——验证角色映射 */
    private static final String[] LEFT_NAMES = {
            "arm_left_elbow_joint", "arm_left_shoulder_lift_joint", "arm_left_shoulder_pan_joint",
            "arm_left_wrist_1_joint", "arm_left_wrist_2_joint", "arm_left_wrist_3_joint",
    };
    /** 数值命名（joint_1..6 兜底路径），字母序恰好等于角色顺序 */
    private static final String[] RIGHT_NAMES = {
            "arm_right_joint_1", "arm_right_joint_2", "arm_right_joint_3",
            "arm_right_joint_4", "arm_right_joint_5", "arm_right_joint_6",
    };

    private static SimAgvTelemetry.JointSnapshot joints(double leftOffset, double rightOffset) {
        String[] names = new String[12];
        double[] positions = new double[12];
        for (int i = 0; i < 6; i++) {
            names[i] = LEFT_NAMES[i];
            positions[i] = LEFT_HOME_ALPHABETICAL[i] + leftOffset;
            names[6 + i] = RIGHT_NAMES[i];
            positions[6 + i] = HOME[i] + rightOffset;
        }
        return new SimAgvTelemetry.JointSnapshot(names, positions, Instant.now());
    }

    private ArmStateMonitor monitor(SimAgvTelemetry telemetry, AtomicInteger inFlight, SimAgvProperties props) {
        return new ArmStateMonitor(telemetry, inFlight::get, props);
    }

    @Test
    void homedWhenAllJointsWithinTolerance() {
        SimAgvTelemetry telemetry = new SimAgvTelemetry();
        telemetry.setJointStates(joints(0.02, -0.02));
        ArmStateMonitor monitor = monitor(telemetry, new AtomicInteger(0), new SimAgvProperties());
        assertThat(monitor.state()).isEqualTo(ArmStateMonitor.ArmState.HOME);
        assertThat(monitor.movementAllowed()).isTrue();
    }

    @Test
    void deployedWhenAnyJointBeyondTolerance() {
        SimAgvTelemetry telemetry = new SimAgvTelemetry();
        telemetry.setJointStates(joints(0.02, 0.9));
        ArmStateMonitor monitor = monitor(telemetry, new AtomicInteger(0), new SimAgvProperties());
        assertThat(monitor.state()).isEqualTo(ArmStateMonitor.ArmState.DEPLOYED);
        assertThat(monitor.movementAllowed()).isFalse();
    }

    @Test
    void movingWinsWhileArmMoveInFlight() {
        SimAgvTelemetry telemetry = new SimAgvTelemetry();
        telemetry.setJointStates(joints(0.02, 0.02));
        ArmStateMonitor monitor = monitor(telemetry, new AtomicInteger(1), new SimAgvProperties());
        assertThat(monitor.state()).isEqualTo(ArmStateMonitor.ArmState.MOVING);
        assertThat(monitor.movementAllowed()).isFalse();
    }

    @Test
    void unknownWithoutJointsPolicyDependsOnStrictFlag() {
        SimAgvTelemetry telemetry = new SimAgvTelemetry();
        SimAgvProperties lenient = new SimAgvProperties();
        ArmStateMonitor lenientMonitor = monitor(telemetry, new AtomicInteger(0), lenient);
        assertThat(lenientMonitor.state()).isEqualTo(ArmStateMonitor.ArmState.UNKNOWN);
        assertThat(lenientMonitor.movementAllowed()).isTrue();

        SimAgvProperties strict = new SimAgvProperties();
        strict.setArmStrictUnknown(true);
        assertThat(monitor(telemetry, new AtomicInteger(0), strict).movementAllowed()).isFalse();
    }

    @Test
    void gateDisabledAlwaysAllows() {
        SimAgvTelemetry telemetry = new SimAgvTelemetry();
        telemetry.setJointStates(joints(1.2, 1.2));
        SimAgvProperties props = new SimAgvProperties();
        props.setArmGateEnabled(false);
        ArmStateMonitor monitor = monitor(telemetry, new AtomicInteger(3), props);
        assertThat(monitor.state()).isEqualTo(ArmStateMonitor.ArmState.HOME);
        assertThat(monitor.movementAllowed()).isTrue();
    }

    @Test
    void mismatchedConfigCountsAsUnknown() {
        SimAgvTelemetry telemetry = new SimAgvTelemetry();
        telemetry.setJointStates(joints(0.0, 0.0));
        SimAgvProperties props = new SimAgvProperties();
        props.setArmHomeRight(new double[]{0, 0, 0}); // 右臂配置角色数不足
        ArmStateMonitor monitor = monitor(telemetry, new AtomicInteger(0), props);
        assertThat(monitor.state()).isEqualTo(ArmStateMonitor.ArmState.UNKNOWN);
    }

    @Test
    void roleMappingIndependentOfNameSortOrder() {
        // 左臂语义名（elbow 字母序在前）+ 右臂数值名：都按角色对位归位角
        SimAgvTelemetry telemetry = new SimAgvTelemetry();
        telemetry.setJointStates(joints(0.02, -0.02));
        ArmStateMonitor monitor = monitor(telemetry, new AtomicInteger(0), new SimAgvProperties());
        assertThat(monitor.state()).isEqualTo(ArmStateMonitor.ArmState.HOME);

        // 左臂 lift 关节（HOME[1]=0.6）偏出容差 → DEPLOYED
        SimAgvTelemetry.JointSnapshot base = joints(0.0, 0.0);
        String[] names = base.names();
        double[] positions = base.positions();
        for (int i = 0; i < names.length; i++) {
            if (names[i].contains("shoulder_lift")) {
                positions[i] = 1.5; // lift 角色远偏离 0.6
            }
        }
        telemetry.setJointStates(new SimAgvTelemetry.JointSnapshot(names, positions, Instant.now()));
        assertThat(monitor.state()).isEqualTo(ArmStateMonitor.ArmState.DEPLOYED);
    }

    @Test
    void singleArmProfileUsesLeftHomeConfig() {
        // 单臂 profile：ur5e_* 原始命名（无 arm/left 标记），按左臂配置判定
        String[] names = {
                "ur5e_elbow_joint", "ur5e_shoulder_lift_joint", "ur5e_shoulder_pan_joint",
                "ur5e_wrist_1_joint", "ur5e_wrist_2_joint", "ur5e_wrist_3_joint",
        };
        double[] alphabeticalHome = LEFT_HOME_ALPHABETICAL.clone();

        SimAgvTelemetry telemetry = new SimAgvTelemetry();
        telemetry.setJointStates(new SimAgvTelemetry.JointSnapshot(
                names.clone(), alphabeticalHome, Instant.now()));
        ArmStateMonitor monitor = monitor(telemetry, new AtomicInteger(0), new SimAgvProperties());
        assertThat(monitor.state()).isEqualTo(ArmStateMonitor.ArmState.HOME);

        double[] deployed = alphabeticalHome.clone();
        deployed[1] = 1.5; // lift 角色偏离 0.6
        telemetry.setJointStates(new SimAgvTelemetry.JointSnapshot(names.clone(), deployed, Instant.now()));
        assertThat(monitor.state()).isEqualTo(ArmStateMonitor.ArmState.DEPLOYED);
    }

    @Test
    void sidePositionsSortedByName() {
        SimAgvTelemetry.JointSnapshot snapshot = joints(0, 0);
        double[] left = ArmStateMonitor.sidePositions(snapshot, "left");
        assertThat(left).hasSize(6);
        // 左臂语义名按字母序排列，其归位角恰为 LEFT_HOME_ALPHABETICAL（角色映射殊途同归）
        for (int i = 0; i < 6; i++) {
            assertThat(left[i]).isEqualTo(LEFT_HOME_ALPHABETICAL[i], org.assertj.core.data.Offset.offset(1e-9));
        }
        assertThat(ArmStateMonitor.sidePositions(snapshot, "waist")).isEmpty();
    }
}
