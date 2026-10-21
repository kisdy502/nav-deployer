package com.agv.navdeployer.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

/**
 * 机械臂归位联锁状态机（真机安全语义：臂未归位时移动作业可能撞击人员/设施）。
 *
 * <p>状态推导（无独立状态源，全部由遥测推出）：
 * <ul>
 *   <li>{@code MOVING}——arm_move 服务调用在途（插值执行中）；</li>
 *   <li>{@code HOME}——各侧臂关节角与配置归位姿态之差均在容差内；</li>
 *   <li>{@code DEPLOYED}——任一臂关节超出容差（展开）；</li>
 *   <li>{@code UNKNOWN}——从未收到 /joint_states，或配置与实际关节数不符。</li>
 * </ul>
 * 移动闸门 {@link #movementAllowed()}：HOME 放行；UNKNOWN 按策略（宽松放行/严格拒绝，
 * 宽松模式兼容不发布 /joint_states 的旧机器人栈）；MOVING/DEPLOYED 一律拒绝。
 */
public class ArmStateMonitor {

    public enum ArmState { HOME, DEPLOYED, MOVING, UNKNOWN }

    private final SimAgvTelemetry telemetry;
    private final IntSupplier armMoveInFlight;
    private final SimAgvProperties props;
    /** 归位运行时覆盖（角色顺序 pan..wrist3，长度=ROLE_COUNT）：前端「设为归位位」经 REST 下发；null=yaml 配置 */
    private volatile double[] homeOverride;

    public ArmStateMonitor(SimAgvTelemetry telemetry, IntSupplier armMoveInFlight, SimAgvProperties props) {
        this.telemetry = telemetry;
        this.armMoveInFlight = armMoveInFlight;
        this.props = props;
    }

    /** 设置归位覆盖（角色顺序，两侧同时生效——单臂 profile 左右同臂）；长度非法忽略。 */
    public void setHomeOverride(double[] home) {
        if (home != null && home.length == ROLE_COUNT) {
            this.homeOverride = home.clone();
        }
    }

    /** 当前归位（角色顺序 pan..wrist3）：运行时覆盖优先，其次 yaml 侧配置。 */
    public double[] homePose() {
        return homeOf(true);
    }

    /** 臂关节名（排序后，与 arm_move 的 joints 顺序契约一致）：双臂取 left，单臂 profile 回退 ur5e_*。 */
    public static List<String> armJointNamesSorted(SimAgvTelemetry.JointSnapshot joints) {
        List<String> dual = sideJointNames(joints, Side.LEFT);
        if (!dual.isEmpty()) {
            return dual;
        }
        return singleArmJointNames(joints);
    }

    /** 归位来源：运行时覆盖（REST「设为归位位」）优先，其次 yaml 侧配置。 */
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
                    // 关节名无法识别语义（非 pan/lift/elbow/wrist 或 joint_N）：无法判定归位
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

    /** 移动作业闸门：HOME 放行；UNKNOWN 按策略；MOVING/DEPLOYED 拒绝。 */
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

    /** 语义角色数：pan / lift / elbow / wrist1 / wrist2 / wrist3 */
    private static final int ROLE_COUNT = 6;
    private static final Pattern JOINT_NUMBER = Pattern.compile("joint[_-]?(\\d)");

    private enum Side { LEFT, RIGHT }

    /**
     * 关节名 → 语义角色下标（0=pan … 5=wrist3）。
     * 归位配置按角色顺序而非名字排序顺序解释——关节名字母序与运动链顺序不一致时
     * （elbow < lift < pan），按序号对位会把归位角错配到别的关节上。
     * 数值命名兜底：joint_1..6 依次对应 pan/lift/elbow/wrist1/wrist2/wrist3。
     */
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

    /** 指定侧臂的关节名列表（/joint_states 按名排序）。 */
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

    /** 单臂 profile 关节名（ur5e_*_joint 原始命名，按名排序）。 */
    private static List<String> singleArmJointNames(SimAgvTelemetry.JointSnapshot joints) {
        List<String> names = new ArrayList<>();
        for (String name : joints.names()) {
            if (name != null && name.startsWith("ur5e_") && name.endsWith("_joint")) {
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

    /** 指定侧臂的关节角（/joint_states 按关节名排序，与 arm_move 的 joints 顺序约定一致）。 */
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
