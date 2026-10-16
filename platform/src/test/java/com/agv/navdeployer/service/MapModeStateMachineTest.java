package com.agv.navdeployer.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切图状态机全路径测试：正常收敛 / 各异常出口 / 超时与失联兜底。
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
}
