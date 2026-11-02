package com.agv.navdeployer.service;

import com.agv.navdeployer.sim.LocalizationOperation;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切图状态机全路径测试：正常收敛 / 各异常出口 / 超时与失联兜底；
 * 新版机器人 operation_id 权威终态通道 + RELOCALIZE 任务类型。
 */
class MapModeStateMachineTest {

    private static final long STALE_LIMIT = 30_000L;

    private MapModeStateMachine machine(String target, long deadlineMillis) {
        return new MapModeStateMachine(1, MapModeStateMachine.Type.SWITCH_MAP,
                42L, "工厂一楼", target, Instant.now().plusMillis(deadlineMillis));
    }

    @Test
    void happyPathDispatchedToRelocatingToSucceeded() {
        MapModeStateMachine machine = machine("map0922", 90_000);

        // 受理后短暂维持旧地图 NAVIGATION：正常窗口，不迁移
        assertEquals(java.util.Optional.empty(), machine.onStatus("NAVIGATION", "old_map", 100, STALE_LIMIT));
        assertEquals(MapModeStateMachine.Status.DISPATCHED, machine.getStatus());

        // 进入 RELOCALIZING
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.RELOCATING),
                machine.onStatus("RELOCALIZING", "map0922", 100, STALE_LIMIT));

        // 收敛回 NAVIGATION 且 map_name=目标 -> SUCCEEDED
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.SUCCEEDED),
                machine.onStatus("NAVIGATION", "map0922", 100, STALE_LIMIT));
        assertTrue(machine.getFinishedAt() != null);
    }

    @Test
    void directNavigationOnTargetSucceedsEvenWithoutRelocalizingFrame() {
        MapModeStateMachine machine = machine("map0922", 90_000);
        // 小地图收敛快，轮询可能直接看到 NAVIGATION+目标
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.SUCCEEDED),
                machine.onStatus("NAVIGATION", "map0922", 100, STALE_LIMIT));
    }

    @Test
    void mappingPreemptsAndFails() {
        MapModeStateMachine machine = machine("map0922", 90_000);
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.FAILED),
                machine.onStatus("MAPPING", "map0922", 100, STALE_LIMIT));
        assertEquals("机器人进入建图模式（MAPPING），切图被抢占", machine.getErrorMessage());
    }

    @Test
    void relayingThenNavigationWithWrongMapFails() {
        MapModeStateMachine machine = machine("map0922", 90_000);
        machine.onStatus("RELOCALIZING", "map0922", 100, STALE_LIMIT);
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.FAILED),
                machine.onStatus("NAVIGATION", "another_map", 100, STALE_LIMIT));
    }

    @Test
    void staleStatusHoldsUntilRecoveryOrDeadline() {
        // 保存地图/重启定位窗口里状态断流：挂起不判死，deadline 兜底
        MapModeStateMachine machine = machine("map0922", 90_000);
        assertEquals(java.util.Optional.empty(),
                machine.onStatus("RELOCALIZING", "map0922", STALE_LIMIT + 1, STALE_LIMIT));
        assertEquals(MapModeStateMachine.Status.DISPATCHED, machine.getStatus());
        // 链路恢复后继续正常跟踪
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.RELOCATING),
                machine.onStatus("RELOCALIZING", "map0922", 100, STALE_LIMIT));
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.SUCCEEDED),
                machine.onStatus("NAVIGATION", "map0922", 100, STALE_LIMIT));
    }

    @Test
    void staleStatusUntilDeadlineFails() {
        // 失联贯穿到 deadline：仍由超时兜底判失败
        MapModeStateMachine machine = machine("map0922", -1); // 已过期
        assertEquals(java.util.Optional.empty(),
                machine.onStatus("RELOCALIZING", "map0922", Long.MAX_VALUE, STALE_LIMIT));
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.FAILED),
                machine.onDeadline(Instant.now()));
    }

    @Test
    void deadlineFails() {
        MapModeStateMachine machine = machine("map0922", -1); // 已过期
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.FAILED),
                machine.onDeadline(Instant.now()));
        assertEquals("SWITCH_MAP 超时（mode 未在期限内达到预期）", machine.getErrorMessage());
    }

    @Test
    void legacyRobotWithoutModeKeepsWaitingUntilDeadline() {
        MapModeStateMachine machine = machine("map0922", 90_000);
        assertEquals(java.util.Optional.empty(), machine.onStatus(null, "map0922", 100, STALE_LIMIT));
        assertEquals(MapModeStateMachine.Status.DISPATCHED, machine.getStatus());
    }

    @Test
    void terminalStateIgnoresFurtherEvents() {
        MapModeStateMachine machine = machine("map0922", 90_000);
        machine.onStatus("NAVIGATION", "map0922", 100, STALE_LIMIT);
        assertEquals(MapModeStateMachine.Status.SUCCEEDED, machine.getStatus());
        assertEquals(java.util.Optional.empty(), machine.onStatus("MAPPING", "x", 100, STALE_LIMIT));
        assertEquals(MapModeStateMachine.Status.SUCCEEDED, machine.getStatus());
    }

    // ==================== START_MAPPING ====================

    private MapModeStateMachine startMappingMachine(long deadlineMillis) {
        return new MapModeStateMachine(2, MapModeStateMachine.Type.START_MAPPING,
                null, null, null, Instant.now().plusMillis(deadlineMillis));
    }

    @Test
    void startMappingSucceedsOnMappingMode() {
        MapModeStateMachine machine = startMappingMachine(90_000);
        // 受理后短暂 NAVIGATION：正常窗口
        assertEquals(java.util.Optional.empty(), machine.onStatus("NAVIGATION", "", 100, STALE_LIMIT));
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.SUCCEEDED),
                machine.onStatus("MAPPING", "", 100, STALE_LIMIT));
    }

    @Test
    void startMappingPreemptedByRelocalizing() {
        MapModeStateMachine machine = startMappingMachine(90_000);
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.FAILED),
                machine.onStatus("RELOCALIZING", "map0922", 100, STALE_LIMIT));
        assertEquals("机器人进入切图流程（RELOCALIZING），建图被抢占", machine.getErrorMessage());
    }

    // ==================== SAVE_MAP ====================

    private MapModeStateMachine saveMapMachine(long deadlineMillis) {
        return new MapModeStateMachine(3, MapModeStateMachine.Type.SAVE_MAP,
                null, "map_new", "map_new", Instant.now().plusMillis(deadlineMillis));
    }

    @Test
    void saveMapHappyPathMappingThenRelocatingThenNavigation() {
        MapModeStateMachine machine = saveMapMachine(90_000);

        // 保存/转换执行中：mode 维持 MAPPING，不迁移、不失败
        assertEquals(java.util.Optional.empty(), machine.onStatus("MAPPING", "map_new", 100, STALE_LIMIT));
        assertEquals(MapModeStateMachine.Status.DISPATCHED, machine.getStatus());

        // 保存完成，机器人切回定位
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.RELOCATING),
                machine.onStatus("RELOCALIZING", "map_new", 100, STALE_LIMIT));
        // 收敛 -> 成功（服务层随后自动 get_map 同步入库）
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.SUCCEEDED),
                machine.onStatus("NAVIGATION", "map_new", 100, STALE_LIMIT));
    }

    @Test
    void saveMapRelayingThenNavigationWithWrongMapFails() {
        MapModeStateMachine machine = saveMapMachine(90_000);
        machine.onStatus("MAPPING", "map_new", 100, STALE_LIMIT);
        machine.onStatus("RELOCALIZING", "map_new", 100, STALE_LIMIT);
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.FAILED),
                machine.onStatus("NAVIGATION", "other_map", 100, STALE_LIMIT));
    }

    @Test
    void saveMapTimeoutWhenRobotStaysMapping() {
        // 机器人保存失败时维持 MAPPING（只清除内部标志），上位机只能靠超时兜底
        MapModeStateMachine machine = saveMapMachine(-1); // 已过期
        machine.onStatus("MAPPING", "map_new", 100, STALE_LIMIT);
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.FAILED),
                machine.onDeadline(Instant.now()));
    }

    // ==================== DISCARD_MAPPING（放弃建图回旧图） ====================

    private MapModeStateMachine discardMachine(long deadlineMillis) {
        return new MapModeStateMachine(4, MapModeStateMachine.Type.DISCARD_MAPPING,
                9L, "map_old", "map_old", Instant.now().plusMillis(deadlineMillis));
    }

    @Test
    void discardHappyPathMappingThenRelocatingThenBackToOldMap() {
        MapModeStateMachine machine = discardMachine(90_000);

        // load_map 终止建图轨迹执行中：mode 维持 MAPPING 是正常过程
        assertEquals(java.util.Optional.empty(), machine.onStatus("MAPPING", "", 100, STALE_LIMIT));
        assertEquals(MapModeStateMachine.Status.DISPATCHED, machine.getStatus());

        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.RELOCATING),
                machine.onStatus("RELOCALIZING", "map_old", 100, STALE_LIMIT));
        // 回到旧图定位收敛 -> 成功（服务层随后把 DB ACTIVE 对齐回旧图）
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.SUCCEEDED),
                machine.onStatus("NAVIGATION", "map_old", 100, STALE_LIMIT));
    }

    @Test
    void discardRelayingThenNavigationWithWrongMapFails() {
        MapModeStateMachine machine = discardMachine(90_000);
        machine.onStatus("MAPPING", "", 100, STALE_LIMIT);
        machine.onStatus("RELOCALIZING", "map_old", 100, STALE_LIMIT);
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.FAILED),
                machine.onStatus("NAVIGATION", "other_map", 100, STALE_LIMIT));
    }

    @Test
    void discardTimeoutWhenRobotStaysMapping() {
        // 机器人侧未处理 load_map（建图终止失败）时维持 MAPPING，只能靠超时兜底
        MapModeStateMachine machine = discardMachine(-1); // 已过期
        machine.onStatus("MAPPING", "", 100, STALE_LIMIT);
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.FAILED),
                machine.onDeadline(Instant.now()));
    }

    // ==================== 定位操作记录（operation_id）权威通道 ====================

    private LocalizationOperation operation(String operationId, String state, String stage,
                                            boolean finished, boolean success, String message) {
        return new LocalizationOperation(operationId, "LOAD_MAP", "map0922", state, stage,
                finished, success, message, true, false, 3.2, Instant.now());
    }

    @Test
    void switchMapWithOperationIgnoresModeSuccessAndWaitsAuthority() {
        MapModeStateMachine machine = machine("map0922", 90_000);
        machine.attachOperation("op-1", null, null);
        assertTrue(machine.hasOperation());

        // mode 已到 NAVIGATION+目标图：带 operation_id 时不再据此判成功（文档：
        // mode 变为 NAVIGATION、map_name 更新不单独证明加载成功），也不迁移进度态
        assertEquals(java.util.Optional.empty(),
                machine.onStatus("NAVIGATION", "map0922", 100, STALE_LIMIT));
        assertEquals(MapModeStateMachine.Status.DISPATCHED, machine.getStatus());

        // 操作记录 RUNNING：驱动到执行中并刷新阶段进度，不下结论
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.RELOCATING), machine.onOperation(
                operation("op-1", "RUNNING", "WAITING_LOCALIZATION", false, false, "等待定位收敛")));
        assertEquals("WAITING_LOCALIZATION", machine.getStage());
        assertEquals("等待定位收敛", machine.getDetailMessage());
        assertEquals(MapModeStateMachine.Status.RELOCATING, machine.getStatus());

        // 操作记录 SUCCEEDED：权威终态
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.SUCCEEDED),
                machine.onOperation(operation("op-1", "SUCCEEDED", "FINISHED", true, true, "定位检查通过")));
        assertTrue(machine.getFinishedAt() != null);
    }

    @Test
    void switchMapWithOperationFailedCarriesRobotReason() {
        MapModeStateMachine machine = machine("map0922", 90_000);
        machine.attachOperation("op-2", null, null);
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.FAILED),
                machine.onOperation(operation("op-2", "FAILED", "STARTING_LOCALIZATION",
                        true, false, "定位进程启动失败")));
        assertEquals("定位进程启动失败", machine.getErrorMessage());

        // 终态后迟到回调不改写
        assertEquals(java.util.Optional.empty(), machine.onOperation(
                operation("op-2", "RUNNING", "WAITING_LOCALIZATION", false, false, "迟到")));
        assertEquals(MapModeStateMachine.Status.FAILED, machine.getStatus());
    }

    @Test
    void timedOutOperationFailsWithTimeoutReason() {
        MapModeStateMachine machine = machine("map0922", 90_000);
        machine.attachOperation("op-3", null, null);
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.FAILED),
                machine.onOperation(operation("op-3", "TIMED_OUT", "WAITING_LOCALIZATION",
                        true, false, "")));
        assertEquals("机器人侧定位操作超时（TIMED_OUT）", machine.getErrorMessage());
    }

    @Test
    void operationOfOtherIdNotConsumed() {
        MapModeStateMachine machine = machine("map0922", 90_000);
        machine.attachOperation("op-mine", null, null);
        assertEquals(java.util.Optional.empty(), machine.onOperation(
                operation("op-other", "SUCCEEDED", "FINISHED", true, true, "别人的操作")));
        assertEquals(MapModeStateMachine.Status.DISPATCHED, machine.getStatus());
    }

    @Test
    void deadlineExtendableForAuthorityChannel() {
        MapModeStateMachine machine = machine("map0922", 1); // 1ms 后到期
        machine.attachOperation("op-4", null, null);
        machine.extendDeadlineTo(Instant.now().plusMillis(60_000));
        // 原始 deadline 已过，但延长后的兜底未到：等待权威结论
        assertEquals(java.util.Optional.empty(), machine.onDeadline(Instant.now()));
        // 操作终态仍可正常落地
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.SUCCEEDED),
                machine.onOperation(operation("op-4", "SUCCEEDED", "FINISHED", true, true, "ok")));
    }

    // ==================== RELOCALIZE（重定位任务） ====================

    private MapModeStateMachine relocalizeMachine(long deadlineMillis) {
        return new MapModeStateMachine(5, MapModeStateMachine.Type.RELOCALIZE,
                null, "map0922", "map0922", Instant.now().plusMillis(deadlineMillis));
    }

    @Test
    void relocalizeLegacyFallbackNeedsRelocalizingFirst() {
        MapModeStateMachine machine = relocalizeMachine(90_000);
        // 受理后 mode 尚未翻转（仍在 NAVIGATION+本图）：不得误判成功
        assertEquals(java.util.Optional.empty(), machine.onStatus("NAVIGATION", "map0922", 100, STALE_LIMIT));
        assertEquals(MapModeStateMachine.Status.DISPATCHED, machine.getStatus());
        // 见过 RELOCALIZING 后回 NAVIGATION：旧版机器人 fallback 判成功
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.RELOCATING),
                machine.onStatus("RELOCALIZING", "map0922", 100, STALE_LIMIT));
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.SUCCEEDED),
                machine.onStatus("NAVIGATION", "map0922", 100, STALE_LIMIT));
    }

    @Test
    void relocalizeWithOperationAuthoritative() {
        MapModeStateMachine machine = relocalizeMachine(90_000);
        machine.attachOperation("op-reloc", null, null);
        // RUNNING 心跳 → 执行中
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.RELOCATING), machine.onOperation(
                operation("op-reloc", "RUNNING", "STOPPING_LOCALIZATION", false, false, "停止旧定位")));
        // mode 流转不判成功
        assertEquals(java.util.Optional.empty(), machine.onStatus("NAVIGATION", "map0922", 100, STALE_LIMIT));
        // 权威失败：机器人原因透传
        LocalizationOperation failed = new LocalizationOperation("op-reloc", "RELOCALIZE", "map0922",
                "FAILED", "STARTING_LOCALIZATION", true, false, "pbstream 加载失败",
                false, false, 8.0, Instant.now());
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.FAILED), machine.onOperation(failed));
        assertEquals("pbstream 加载失败", machine.getErrorMessage());
    }

    @Test
    void relocalizePreemptedByMappingFails() {
        MapModeStateMachine machine = relocalizeMachine(90_000);
        assertEquals(java.util.Optional.of(MapModeStateMachine.Status.FAILED),
                machine.onStatus("MAPPING", "map0922", 100, STALE_LIMIT));
        assertEquals("机器人进入建图模式（MAPPING），重定位被抢占", machine.getErrorMessage());
    }

    @Test
    void progressVersionBumpsOnlyOnStageOrMessageChange() {
        MapModeStateMachine machine = machine("map0922", 90_000);
        machine.attachOperation("op-p", null, null);
        // 首条 RUNNING 心跳把 stage 从 null 变为值：计一次
        machine.onOperation(operation("op-p", "RUNNING", "WAITING_LOCALIZATION", false, false, "等待"));
        int before = machine.getProgressVersion();
        // 相同 stage/message 不重复计数（1s 心跳重发不产生 SSE 抖动）
        machine.onOperation(operation("op-p", "RUNNING", "WAITING_LOCALIZATION", false, false, "等待"));
        machine.onOperation(operation("op-p", "RUNNING", "WAITING_LOCALIZATION", false, false, "等待"));
        assertEquals(before, machine.getProgressVersion());
        // stage 变化才计数
        machine.onOperation(operation("op-p", "RUNNING", "STARTING_LOCALIZATION", false, false, "等待"));
        assertEquals(before + 1, machine.getProgressVersion());
    }
}
