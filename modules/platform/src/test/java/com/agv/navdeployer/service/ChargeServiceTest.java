package com.agv.navdeployer.service;

import com.agv.navdeployer.metadata.mapper.MapWaypointMapper;
import com.agv.navdeployer.sim.RosCommandDispatcher;
import com.agv.navdeployer.sim.SimAgvSsePublisher;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import com.agv.navdeployer.vo.ChargeTaskVO;
import com.agv.navdeployer.vo.MoveTaskVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 充电任务状态机：到桩导航 → set_charging 服务 → 遥测确认（对接成功）即任务完成；
 * 失败/取消路径与判定纪律（服务成功≠完成，只认 charging=true 遥测）。
 * 充到多少电量、何时断充由上层调度控制——任务不跟踪电量目标。
 */
class ChargeServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MOVE_TASK_ID = 7L;

    private final SmartNavigationService smartNavigation = mock(SmartNavigationService.class);
    private final MoveTaskService moves = mock(MoveTaskService.class);
    private final RosCommandDispatcher dispatcher = mock(RosCommandDispatcher.class);
    private final SimAgvTelemetry telemetry = new SimAgvTelemetry();
    private final SimAgvSsePublisher sse = mock(SimAgvSsePublisher.class);
    private final MapWaypointMapper waypointMapper = mock(MapWaypointMapper.class);
    private final RobotOperationalService operational = mock(RobotOperationalService.class);

    private final ChargeProperties props = new ChargeProperties();
    private ChargeService service;

    private final AtomicReference<String> moveStatus = new AtomicReference<>("EXECUTING");

    private ScheduledExecutorService shutdownTarget;

    private ChargeService build() throws Exception {
        props.setPollIntervalMs(50);
        props.setConfirmTimeoutMs(500);
        props.setMaxDurationMs(60000);
        props.setDockSettleMinMs(100);
        props.setDockSettleMaxMs(2000);
        when(operational.isDecommissioned()).thenReturn(false);
        // 导航与 navigate 任务同构：单段（createOptimizedMoveTask）
        when(smartNavigation.createOptimizedMoveTask(anyString(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> moveTask(MOVE_TASK_ID, moveStatus.get()));
        when(moves.get(MOVE_TASK_ID)).thenAnswer(inv -> moveTask(MOVE_TASK_ID, moveStatus.get()));
        service = new ChargeService(smartNavigation, moves, dispatcher, telemetry,
                sse, props, waypointMapper, operational);
        var worker = ChargeService.class.getDeclaredField("worker");
        worker.setAccessible(true);
        shutdownTarget = (ScheduledExecutorService) worker.get(service);
        return service;
    }

    @AfterEach
    void tearDown() {
        if (shutdownTarget != null) {
            shutdownTarget.shutdownNow();
        }
    }

    private static MoveTaskVO moveTask(long id, String status) {
        MoveTaskVO vo = new MoveTaskVO();
        vo.setId(id);
        vo.setStatus(status);
        if ("FAILED".equals(status) || "TIMEOUT".equals(status)) {
            vo.setErrorMessage("robot navigation " + status.toLowerCase());
        }
        return vo;
    }

    private void telemetryBattery(double pct, Boolean charging) {
        telemetry.setStatus(new SimAgvTelemetry.StatusSnapshot(
                "AGV001", "IDLE", pct, true, "", "", "NAVIGATION", "map",
                charging == null ? null : (charging ? "CHARGING" : "DISCHARGING"),
                charging, charging, true, Instant.now()));
    }

    /** 机器人位姿置于充电桩上（测试目标坐标 1.0, 2.0）——通过到桩校验 */
    private void atDock() {
        telemetry.setMapPose(new SimAgvTelemetry.PoseSnapshot(1.0, 2.0, 0.5, 0, Instant.now()));
    }

    /** 机器人位姿距充电桩 2m（模拟路线终点吸附在 P4，未到桩 C1） */
    private void awayFromDock() {
        telemetry.setMapPose(new SimAgvTelemetry.PoseSnapshot(3.0, 2.0, 0.5, 0, Instant.now()));
    }

    /** stub set_charging 服务响应 */
    private void stubChargeService(JsonNode response) {
        when(dispatcher.callService(eq(props.getService()), any(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private static JsonNode serviceResponse(boolean success, String message) {
        try {
            return MAPPER.readTree("{\"success\":" + success + ",\"message\":\"" + message + "\"}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ChargeTaskVO awaitStatus(long id, String expected) {
        return await(() -> {
            ChargeTaskVO vo = service.get(id);
            return vo != null && expected.equals(vo.status()) ? vo : null;
        }, 5000);
    }

    private static <T> T await(Supplier<T> condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        T value;
        while (System.currentTimeMillis() < deadline) {
            value = condition.get();
            if (value != null) {
                return value;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        throw new AssertionError("condition not met within " + timeoutMs + "ms");
    }

    /**
     * 对接成功即完成：遥测 charging=true 确认后任务直接 SUCCEEDED（不等电量目标，
     * 否则会阻塞调度后续进程——如「充到 60% 允许作业」），且不调 data=false（留桩继续充）。
     */
    @Test
    void happyPathCompletesOnChargingConfirmed() throws Exception {
        build();
        stubChargeService(serviceResponse(true, ""));
        telemetryBattery(42.0, false);
        atDock();

        ChargeTaskVO task = service.start(new ChargeService.ChargeCommand("test", null, 1.0, 2.0, 0.5, null));
        assertThat(task.status()).isEqualTo("MOVING");

        // 到桩 → 开始充电服务（data=true）
        moveStatus.set("SUCCEEDED");
        awaitStatus(task.id(), "STARTING");
        // 遥测 charging=true → 对接确认 → SUCCEEDED（当前电量 42%，远未充满）
        telemetryBattery(42.0, true);
        ChargeTaskVO done = awaitStatus(task.id(), "SUCCEEDED");
        assertThat(done.message()).contains("对接充电桩").contains("充电已开始");
        assertThat(done.batteryAtStart()).isEqualTo(42.0);
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(dispatcher, atLeastOnce()).callService(eq(props.getService()), captor.capture(), anyLong());
        assertThat(captor.getAllValues()).isNotEmpty();
        assertThat(captor.getAllValues()).allSatisfy(args ->
                assertThat(args.path("data").asBoolean()).as("只应发送 data=true").isTrue());
    }

    /** 机器人拒绝开始充电（服务返回 success=false，如未对接好）→ 任务 FAILED 并带拒绝原因。 */
    @Test
    void chargeServiceRejectionFailsTask() throws Exception {
        build();
        stubChargeService(serviceResponse(false, "not docked"));
        telemetryBattery(42.0, false);
        atDock();
        ChargeTaskVO task = service.start(new ChargeService.ChargeCommand("test", null, 1.0, 2.0, 0.5, null));
        moveStatus.set("SUCCEEDED");
        ChargeTaskVO failed = await(() -> {
            ChargeTaskVO vo = service.get(task.id());
            return vo != null && "FAILED".equals(vo.status()) ? vo : null;
        }, 5000);
        assertThat(failed.message()).contains("拒绝开始充电").contains("not docked");
    }

    /** 到桩导航失败（move task FAILED）→ 充电任务 FAILED，报「导航到充电点失败」。 */
    @Test
    void navigationFailureFailsTask() throws Exception {
        build();
        telemetryBattery(42.0, false);
        ChargeTaskVO task = service.start(new ChargeService.ChargeCommand("test", null, 1.0, 2.0, 0.5, null));
        moveStatus.set("FAILED");
        ChargeTaskVO failed = await(() -> {
            ChargeTaskVO vo = service.get(task.id());
            return vo != null && "FAILED".equals(vo.status()) ? vo : null;
        }, 5000);
        assertThat(failed.message()).contains("导航到充电点失败");
    }

    /** 服务返回成功但遥测一直未确认 charging=true → 确认超时任务 FAILED（服务成功≠完成）。 */
    @Test
    void telemetryConfirmTimeoutFailsTask() throws Exception {
        build();
        // 服务成功但遥测一直未确认 charging=true
        stubChargeService(serviceResponse(true, ""));
        telemetryBattery(42.0, false);
        atDock();
        ChargeTaskVO task = service.start(new ChargeService.ChargeCommand("test", null, 1.0, 2.0, 0.5, null));
        moveStatus.set("SUCCEEDED");
        ChargeTaskVO failed = await(() -> {
            ChargeTaskVO vo = service.get(task.id());
            return vo != null && "FAILED".equals(vo.status()) ? vo : null;
        }, 5000);
        assertThat(failed.message()).contains("充电启动未确认");
    }

    /** 对接阶段（STARTING）手动取消：任务 CANCELLED 且尽力断充（set_charging data=false） */
    @Test
    void manualCancelDuringStartingStopsCharging() throws Exception {
        build();
        stubChargeService(serviceResponse(true, ""));
        telemetryBattery(42.0, false);
        atDock();
        ChargeTaskVO task = service.start(new ChargeService.ChargeCommand("test", null, 1.0, 2.0, 0.5, null));
        moveStatus.set("SUCCEEDED");
        awaitStatus(task.id(), "STARTING");

        ChargeTaskVO cancelled = service.cancel("手动取消");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        // 取消要尽力断充：set_charging data=false 已下发
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(dispatcher, atLeastOnce()).callService(eq(props.getService()), captor.capture(), anyLong());
        boolean stopSent = captor.getAllValues().stream()
                .anyMatch(args -> !args.path("data").asBoolean(true));
        assertThat(stopSent).as("cancel 应发送 set_charging data=false").isTrue();
    }

    /** 远程停止充电（任务外断充入口）：不依赖任务存在，直接下发 data=false */
    @Test
    void stopChargingSendsDataFalse() throws Exception {
        build();
        when(dispatcher.callService(eq(props.getService()), any(), anyLong()))
                .thenReturn(new CompletableFuture<>());
        service.stopCharging();
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(dispatcher).callService(eq(props.getService()), captor.capture(), anyLong());
        assertThat(captor.getValue().path("data").asBoolean(true)).isFalse();
    }

    /**
     * 导航与 navigate 任务同构（单段）：内部只派发一个 move task，完成即到桩校验开始充电，
     * 不存在多段路线接力——多段到桩路线由调度逐段下发 navigate（与质检流程一致）。
     */
    @Test
    void navigationIsSingleSegmentLikeNavigateTask() throws Exception {
        build();
        stubChargeService(serviceResponse(true, ""));
        telemetryBattery(42.0, false);
        atDock();

        ChargeTaskVO task = service.start(new ChargeService.ChargeCommand("test", null, 1.0, 2.0, 0.5, null));
        assertThat(task.status()).isEqualTo("MOVING");
        // 只有一次 createOptimizedMoveTask，不查路线链、不派发 FOLLOW_PATH
        verify(smartNavigation).createOptimizedMoveTask(anyString(), anyDouble(), anyDouble(), anyDouble(), any());
        verify(smartNavigation, never()).planRoutePathIds(anyString(), anyDouble(), anyDouble());
        verify(smartNavigation, never()).createFollowPathTask(anyLong(), any());

        // 单段完成 → 到桩校验 → 停稳 → 对接确认 → 完成
        moveStatus.set("SUCCEEDED");
        telemetryBattery(42.0, true);
        ChargeTaskVO done = awaitStatus(task.id(), "SUCCEEDED");
        assertThat(done.message()).contains("充电已开始");
    }

    /** 到桩后须等 odom 速度归零（停稳）才调 set_charging——运动中调用会被机器人拒绝。 */
    @Test
    void dockWaitsForOdomQuietBeforeStartCharging() throws Exception {
        build();
        stubChargeService(serviceResponse(true, ""));
        telemetryBattery(42.0, false);
        atDock();
        // /odom 在流但速度非零（末端旋转/减速残余运动）：机器人侧会拒绝运动中的充电请求
        telemetry.setOdomPose(new SimAgvTelemetry.PoseSnapshot(0, 0, 0, 0, Instant.now()));
        telemetry.setOdomVelocity(0.4, 0.0, 0.0);

        ChargeTaskVO task = service.start(new ChargeService.ChargeCommand("test", null, 1.0, 2.0, 0.5, null));
        moveStatus.set("SUCCEEDED");
        awaitStatus(task.id(), "STARTING");

        // 超过最小停稳等待（100ms）但速度非零 → 不得调 set_charging
        TimeUnit.MILLISECONDS.sleep(600);
        verify(dispatcher, never()).callService(anyString(), any(), anyLong());

        // 速度归零（停稳）→ 触发服务并对接确认 → 完成
        telemetry.setOdomVelocity(0.0, 0.0, 0.0);
        verify(dispatcher, timeout(3000)).callService(eq(props.getService()), any(), anyLong());
        telemetryBattery(42.0, true);
        awaitStatus(task.id(), "SUCCEEDED");
    }

    /** 救援充电（电量耗尽死锁）跳过导航原地开始充电，不经任何 move task 派发。 */
    @Test
    void rescueSkipsNavigationAndChargesDirectly() throws Exception {
        build();
        stubChargeService(serviceResponse(true, ""));
        telemetryBattery(0.0, false); // 电量耗尽死锁场景
        ChargeTaskVO task = service.startRescue();
        // 不经导航：直接进入开始充电阶段
        assertThat(task.status()).isEqualTo("STARTING");
        assertThat(task.source()).isEqualTo("rescue");
        // 停稳等待（odom 不可用 → 纯延时）后调服务 → 遥测确认（对接成功即完成）
        telemetryBattery(1.0, true);
        ChargeTaskVO done = awaitStatus(task.id(), "SUCCEEDED");
        assertThat(done.message()).contains("充电已开始");
        verify(smartNavigation, never()).createGoalTask(anyDouble(), anyDouble(), anyDouble(), any());
        verify(smartNavigation, never()).createFollowPathTask(anyLong(), any());
    }

    /** 已有进行中的充电任务时再次发起被拒绝，防止并发充电任务。 */
    @Test
    void secondStartRejectedWhileActive() throws Exception {
        build();
        telemetryBattery(42.0, false);
        service.start(new ChargeService.ChargeCommand("test", null, 1.0, 2.0, 0.5, null));
        assertThatThrownBy(() -> service.start(new ChargeService.ChargeCommand("test2", null, 3.0, 4.0, 0.0, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已有进行中的充电任务");
    }

    /** 任务历史：终态任务留档（最新在前），进行中任务置顶。 */
    @Test
    void historyRecordsTerminalAndActiveTasks() throws Exception {
        build();
        telemetryBattery(42.0, false);
        ChargeTaskVO first = service.start(new ChargeService.ChargeCommand("test", null, 1.0, 2.0, 0.5, null));
        moveStatus.set("FAILED");
        await(() -> {
            ChargeTaskVO vo = service.get(first.id());
            return vo != null && "FAILED".equals(vo.status()) ? vo : null;
        }, 5000);

        // 终态后可发新任务；进行中的新任务在历史置顶
        moveStatus.set("EXECUTING");
        ChargeTaskVO second = service.start(new ChargeService.ChargeCommand("test2", null, 3.0, 4.0, 0.0, null));
        java.util.List<ChargeTaskVO> history = service.history(10);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).id()).isEqualTo(second.id());
        assertThat(history.get(0).status()).isEqualTo("MOVING");
        assertThat(history.get(1).id()).isEqualTo(first.id());
        assertThat(history.get(1).status()).isEqualTo("FAILED");
    }

    // ==================== 到桩校验（未到桩绝不开始充电） ====================

    /**
     * 场景复现：单段导航终点吸附在离桩数米的 waypoint（如 P4），move "成功"但机器人不在桩上。
     * 行为（对齐质检式分段导航语义）：不补段、直接到桩校验 → 超出半径任务 FAILED，
     * 绝不调 set_charging。多段到桩路线应由调度逐段 navigate，最后一段精确到桩。
     */
    @Test
    void notAtDockRejectedWithoutApproach() throws Exception {
        build();
        telemetryBattery(42.0, false);
        awayFromDock(); // 机器人停在 P4，距桩 (1.0, 2.0) 2m

        ChargeTaskVO task = service.start(new ChargeService.ChargeCommand("rms:t1", null, 1.0, 2.0, 0.5, null));
        moveStatus.set("SUCCEEDED");
        ChargeTaskVO failed = await(() -> {
            ChargeTaskVO vo = service.get(task.id());
            return vo != null && "FAILED".equals(vo.status()) ? vo : null;
        }, 5000);
        assertThat(failed.message()).contains("未到达充电桩").contains("超过允许半径");
        // 全程绝不调 set_charging（机器人侧不校验位置，上位机必须拦住）
        verify(dispatcher, never()).callService(anyString(), any(), anyLong());
        // 单段语义：只有 start 时的一次导航派发，不补接近段
        verify(smartNavigation).createOptimizedMoveTask(anyString(), anyDouble(), anyDouble(), anyDouble(), any());
    }

    /** 无位姿（/tf 未上报）同样拒绝：校验不了就不能开始充电 */
    @Test
    void missingPoseRejectsCharging() throws Exception {
        build();
        telemetryBattery(42.0, false);
        // 不设置 map pose
        ChargeTaskVO task = service.start(new ChargeService.ChargeCommand("test", null, 1.0, 2.0, 0.5, null));
        moveStatus.set("SUCCEEDED");
        ChargeTaskVO failed = await(() -> {
            ChargeTaskVO vo = service.get(task.id());
            return vo != null && "FAILED".equals(vo.status()) ? vo : null;
        }, 5000);
        assertThat(failed.message()).contains("无法获取机器人当前位姿");
        verify(dispatcher, never()).callService(anyString(), any(), anyLong());
    }
}
