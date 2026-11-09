package com.agv.navdeployer.rms.application;

import com.agv.navdeployer.rms.domain.RmsTask;
import com.agv.navdeployer.rms.domain.RmsTaskRegistry;
import com.agv.navdeployer.rms.domain.RmsTaskStatus;
import com.agv.navdeployer.rms.infrastructure.config.RmsProperties;
import com.agv.navdeployer.robot.domain.ArmStateMonitor;
import com.agv.navdeployer.robot.domain.SimAgvProperties;
import com.agv.navdeployer.robot.domain.SimAgvTelemetry;
import com.agv.navdeployer.robot.infrastructure.RosCommandDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * 机械臂质检序列：展开到质检位 → 保持 → 收拢归位，全部经 arm_move 下发并以关节位置闭环确认。
 */
@Component
public class RmsArmSequencer {

    private static final Logger log = LoggerFactory.getLogger(RmsArmSequencer.class);
    private static final double ARM_PHASE_SEC = 6.0;

    private final RmsTaskRegistry registry;
    private final RmsProperties props;
    private final SimAgvTelemetry armTelemetry;
    private final ArmStateMonitor armStateMonitor;
    private final SimAgvProperties simProps;
    private final RosCommandDispatcher armDispatcher;
    private final java.util.concurrent.ScheduledExecutorService worker;

    public RmsArmSequencer(RmsTaskRegistry registry,
                           RmsProperties props,
                           SimAgvTelemetry armTelemetry,
                           ArmStateMonitor armStateMonitor,
                           SimAgvProperties simProps,
                           RosCommandDispatcher armDispatcher) {
        this.registry = registry;
        this.props = props;
        this.armTelemetry = armTelemetry;
        this.armStateMonitor = armStateMonitor;
        this.simProps = simProps;
        this.armDispatcher = armDispatcher;
        this.worker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rms-arm-seq");
            thread.setDaemon(true);
            return thread;
        });
    }

    boolean armMovementAllowed() {
        return armStateMonitor == null || armStateMonitor.movementAllowed();
    }

    String armState() {
        return armStateMonitor == null ? null : armStateMonitor.state().name();
    }

    public void simulateInspection(RmsTask task, BiConsumer<RmsTask, String> fail,
                                   java.util.function.Consumer<RmsTask> complete) {
        long dwellMs = Math.max(0L, props.getBehavior().getInspectionDurationMs());
        boolean armAvailable = armDispatcher != null && armTelemetry != null
                && armTelemetry.getJointStates() != null;
        log.info("rms task {} simulated inspection start: armSequence={} dwell={}ms",
                task.taskId(), armAvailable, dwellMs);
        worker.execute(() -> {
            if (armAvailable && runInspectionSequence(task, dwellMs, fail, complete)) {
                return;
            }
            if (armAvailable) {
                return;
            }
            if (!dwell(task, dwellMs)) {
                return;
            }
            complete.accept(task);
        });
    }

    private boolean runInspectionSequence(RmsTask task, long dwellMs,
                                          BiConsumer<RmsTask, String> fail,
                                          java.util.function.Consumer<RmsTask> complete) {
        List<String> names = ArmStateMonitor.armJointNamesSorted(armTelemetry.getJointStates());
        double[] inspect = roleTargets(names, simProps.getArmInspectPose());
        double[] home = roleTargets(names,
                armStateMonitor == null ? simProps.getArmHomeLeft() : armStateMonitor.homePose());
        if (names.isEmpty() || inspect == null || home == null) {
            fail.accept(task, "质检位姿配置与关节角色不匹配（names=" + names.size() + "）");
            return true;
        }
        log.info("rms task {} inspection phase 1/3: deploy to inspect pose", task.taskId());
        if (!armPhase(task, names, "deploy(展开质检位)", inspect, fail)) {
            return true;
        }
        if (!dwell(task, dwellMs)) {
            return false;
        }
        log.info("rms task {} inspection phase 3/3: retract to home", task.taskId());
        if (!armPhase(task, names, "retract(收拢归位)", home, fail)) {
            return true;
        }
        complete.accept(task);
        return true;
    }

    private static double[] roleTargets(List<String> names, double[] rolePose) {
        if (rolePose == null || rolePose.length < 6) {
            return null;
        }
        double[] targets = new double[names.size()];
        for (int i = 0; i < names.size(); i++) {
            int role = ArmStateMonitor.roleIndexOf(names.get(i));
            if (role < 0) {
                return null;
            }
            targets[i] = rolePose[role];
        }
        return targets;
    }

    private boolean armPhase(RmsTask task, List<String> names, String label, double[] targets,
                             BiConsumer<RmsTask, String> fail) {
        try {
            RosCommandDispatcher.ArmMoveResult result = armDispatcher.armMove("left", targets, ARM_PHASE_SEC).join();
            if (result == null || !result.success()) {
                fail.accept(task, "arm " + label + " 被拒绝: " + (result == null ? "null" : result.message()));
                return false;
            }
        } catch (Exception exception) {
            fail.accept(task, "arm " + label + " 下发失败: " + exception.getMessage());
            return false;
        }
        // 到位等待必须按墙钟给足余量，否则仿真侧还在走就被判超时（AGV_NODE_FAILED 到位超时）。
        long deadline = System.currentTimeMillis() + Math.max(
                (long) (ARM_PHASE_SEC * 1000) + 8000,
                props.getBehavior().getArmPhaseWallClockTimeoutMs());
        while (System.currentTimeMillis() < deadline) {
            if (!dwell(task, 200)) {
                return false;
            }
            SimAgvTelemetry.JointSnapshot joints = armTelemetry.getJointStates();
            if (joints == null) {
                continue;
            }
            boolean reached = true;
            for (int i = 0; i < names.size(); i++) {
                double actual = ArmStateMonitor.positionOf(joints, names.get(i));
                if (Math.abs(actual - targets[i]) > simProps.getArmHomeToleranceRad()) {
                    reached = false;
                    break;
                }
            }
            if (reached) {
                log.info("rms task {} arm {} reached", task.taskId(), label);
                return true;
            }
        }
        fail.accept(task, "arm " + label + " 到位超时（可能被限位截断）");
        return false;
    }

    private boolean dwell(RmsTask task, long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            try {
                java.util.concurrent.TimeUnit.MILLISECONDS.sleep(
                        Math.min(200, Math.max(1, deadline - System.currentTimeMillis())));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            RmsTask current = registry.find(task.taskId()).orElse(null);
            if (current == null || current.status() != RmsTaskStatus.RUNNING) {
                return false;
            }
        }
        return true;
    }
}
