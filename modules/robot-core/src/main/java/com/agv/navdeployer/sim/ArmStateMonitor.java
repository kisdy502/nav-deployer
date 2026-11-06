package com.agv.navdeployer.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

public class ArmStateMonitor {

    public enum ArmState { HOME, DEPLOYED, MOVING, UNKNOWN }

    private final SimAgvTelemetry telemetry;
    private final IntSupplier armMoveInFlight;
    private final SimAgvProperties props;
    private volatile double[] homeOverride;

    public ArmStateMonitor(SimAgvTelemetry telemetry, IntSupplier armMoveInFlight, SimAgvProperties props) {
        this.telemetry = telemetry;
        this.armMoveInFlight = armMoveInFlight;
        this.props = props;
    }

    public void setHomeOverride(double[] home) {
        if (home != null && home.length == ROLE_COUNT) {
            this.homeOverride = home.clone();
        }
    }

    public double[] homePose() {
        return homeOf(true);
    }

    public static List<String> armJointNamesSorted(SimAgvTelemetry.JointSnapshot joints) {
        List<String> dual = sideJointNames(joints, Side.LEFT);
        if (!dual.isEmpty()) {
            return dual;
        }
        return singleArmJointNames(joints);
    }

    private double[] homeOf(boolean left) {
        double[] override = homeOverride;
        return override != null ? override : (left ? props.getArmHomeLeft() : props.getArmHomeRight());
    }

    public synchronized ArmState state() {
        if (!props.isArmGateEnabled()) {
            return ArmState.HOME;
        }
        if (armMoveInFlight.getAsInt() > 0) {
            return ArmState.MOVING;
        }
        SimAgvTelemetry.JointSnapshot joints = telemetry.getJointStates();
        if (joints == null || joints.names() == null || joints.positions() == null
                || joints.names().length == 0) {
            return ArmState.UNKNOWN;
        }
        boolean anySideSeen = false;
        boolean allHomed = true;
        for (Side side : Side.values()) {
            List<String> names = sideJointNames(joints, side);
            if (names.isEmpty()) {
                continue;
            }
            double[] home = homeOf(side == Side.LEFT);
            if (home == null || home.length < ROLE_COUNT) {
                return ArmState.UNKNOWN;
            }
            anySideSeen = true;
            for (String name : names) {
                int role = roleIndexOf(name);
                if (role < 0) {
                    return ArmState.UNKNOWN;
                }
                double actual = positionOf(joints, name);
                if (Math.abs(actual - home[role]) > props.getArmHomeToleranceRad()) {
                    allHomed = false;
                }
            }
        }
        if (!anySideSeen) {
            // 单臂 profile：UR5e 原始命名（ur5e_*_joint，无 arm/left 标记），
            // 左右都指向同一物理臂——按左臂归位配置判定
            List<String> single = singleArmJointNames(joints);
            if (single.isEmpty()) {
                return ArmState.UNKNOWN;
            }
            double[] home = homeOf(true);
            if (home == null || home.length < ROLE_COUNT) {
                return ArmState.UNKNOWN;
            }
            for (String name : single) {
                int role = roleIndexOf(name);
                if (role < 0) {
                    return ArmState.UNKNOWN;
                }
                if (Math.abs(positionOf(joints, name) - home[role]) > props.getArmHomeToleranceRad()) {
                    return ArmState.DEPLOYED;
                }
            }
            return ArmState.HOME;
        }
        return allHomed ? ArmState.HOME : ArmState.DEPLOYED;
    }

    public boolean movementAllowed() {
        ArmState state = state();
        if (state == ArmState.HOME) {
            return true;
        }
        if (state == ArmState.UNKNOWN) {
            return !props.isArmStrictUnknown();
        }
        return false;
    }

    private static final int ROLE_COUNT = 6;
    private static final Pattern JOINT_NUMBER = Pattern.compile("joint[_-]?(\\d)");

    private enum Side { LEFT, RIGHT }

    // 按语义角色（pan/lift 等）匹配而非关节名字母序：归位配置按角色序存储，错序会把归位角装到错误关节
    public static int roleIndexOf(String jointName) {
        String l = jointName == null ? "" : jointName.toLowerCase(Locale.ROOT);
        if (l.contains("pan")) {
            return 0;
        }
        if (l.contains("lift")) {
            return 1;
        }
        if (l.contains("elbow")) {
            return 2;
        }
        if (l.contains("wrist_1") || l.contains("wrist1")) {
            return 3;
        }
        if (l.contains("wrist_2") || l.contains("wrist2")) {
            return 4;
        }
        if (l.contains("wrist_3") || l.contains("wrist3")) {
            return 5;
        }
        java.util.regex.Matcher matcher = JOINT_NUMBER.matcher(l);
        if (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1)) - 1;
            return index >= 0 && index < ROLE_COUNT ? index : -1;
        }
        return -1;
    }

    private static List<String> sideJointNames(SimAgvTelemetry.JointSnapshot joints, Side side) {
        List<String> names = new ArrayList<>();
        for (String name : joints.names()) {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (lower.contains("arm") && lower.contains(side.name().toLowerCase(Locale.ROOT))) {
                names.add(name);
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    private static final java.util.regex.Pattern SINGLE_ARM_JOINT = java.util.regex.Pattern.compile(
            "ur5e_(shoulder_pan|shoulder_lift|elbow|wrist_1|wrist_2|wrist_3)_joint");

    private static List<String> singleArmJointNames(SimAgvTelemetry.JointSnapshot joints) {
        List<String> names = new ArrayList<>();
        for (String name : joints.names()) {
            if (name != null && SINGLE_ARM_JOINT.matcher(name).matches()) {
                names.add(name);
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    public static double positionOf(SimAgvTelemetry.JointSnapshot joints, String name) {
        for (int k = 0; k < joints.names().length; k++) {
            if (name.equals(joints.names()[k])) {
                return k < joints.positions().length ? joints.positions()[k] : 0;
            }
        }
        return 0;
    }

    static double[] sidePositions(SimAgvTelemetry.JointSnapshot joints, String side) {
        List<String> names = new ArrayList<>();
        for (String name : joints.names()) {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (lower.contains("arm") && lower.contains(side)) {
                names.add(name);
            }
        }
        names.sort(String::compareTo);
        double[] positions = new double[names.size()];
        for (int i = 0; i < names.size(); i++) {
            positions[i] = positionOf(joints, names.get(i));
        }
        return positions;
    }
}
