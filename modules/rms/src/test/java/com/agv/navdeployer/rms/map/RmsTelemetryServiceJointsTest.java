package com.agv.navdeployer.rms.map;

import com.agv.navdeployer.sim.SimAgvTelemetry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pose/query 关节分组测试：/joint_states 混合消息按命名契约拆到
 * left_joints / right_joints / waist_joints，舵轮与未知关节不下发。
 */
class RmsTelemetryServiceJointsTest {

    private SimAgvTelemetry.JointSnapshot joints(String[] names, double[] positions) {
        return new SimAgvTelemetry.JointSnapshot(names, positions, Instant.now());
    }

    @Test
    void groupsAndSortsJointsByName() {
        Map<String, Object> data = new LinkedHashMap<>();
        RmsTelemetryService.appendGroupedJoints(data, joints(
                new String[]{
                        "arm_right_joint_2", "steer_joint_front", "arm_left_joint_1",
                        "waist_joint_2", "arm_left_joint_2", "arm_right_joint_1",
                        "wheel_rear", "waist_joint_1", "arm_left_joint_3",
                },
                new double[]{
                        0.2, 0.5, 1.1,
                        -0.3, 1.2, 0.1,
                        0.9, -0.4, 1.3,
                }));
        assertArrayEquals(new double[]{1.1, 1.2, 1.3}, (double[]) data.get("left_joints"));
        assertArrayEquals(new double[]{0.1, 0.2}, (double[]) data.get("right_joints"));
        assertArrayEquals(new double[]{-0.4, -0.3}, (double[]) data.get("waist_joints"));
        assertTrue(data.size() == 3);
    }

    @Test
    void nullSnapshotYieldsEmptyArrays() {
        Map<String, Object> data = new LinkedHashMap<>();
        RmsTelemetryService.appendGroupedJoints(data, null);
        assertArrayEquals(new double[0], (double[]) data.get("left_joints"));
        assertArrayEquals(new double[0], (double[]) data.get("right_joints"));
        assertArrayEquals(new double[0], (double[]) data.get("waist_joints"));
    }

    @Test
    void caseInsensitiveMatching() {
        Map<String, Object> data = new LinkedHashMap<>();
        RmsTelemetryService.appendGroupedJoints(data, joints(
                new String[]{"Arm_Left_Joint_1", "ARM_RIGHT_JOINT_1", "Waist_Joint_1"},
                new double[]{0.1, 0.2, 0.3}));
        assertArrayEquals(new double[]{0.1}, (double[]) data.get("left_joints"));
        assertArrayEquals(new double[]{0.2}, (double[]) data.get("right_joints"));
        assertArrayEquals(new double[]{0.3}, (double[]) data.get("waist_joints"));
    }
    @Test
    void singleUr5eReportsActualJoints() {
        Map<String, Object> data = new LinkedHashMap<>();
        RmsTelemetryService.appendGroupedJoints(data, joints(
                new String[]{"ur5e_wrist_3_joint", "ur5e_elbow_joint", "wheel_joint"},
                new double[]{0.3, 0.1, 99}));
        assertArrayEquals(new double[]{0.1, 0.3}, (double[]) data.get("left_joints"));
        assertArrayEquals(new double[0], (double[]) data.get("right_joints"));
        assertArrayEquals(new double[0], (double[]) data.get("waist_joints"));
    }
}
