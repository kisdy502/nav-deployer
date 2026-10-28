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
 * 充电任务状态机：到桩导航 → set_charging 服务 → 遥测确认 → 充到目标电量完成；
 * 失败/取消/被打断路径与判定纪律（服务成功≠完成，只认有效遥测）。
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
    private final AtomicReference<MoveTaskVO> activeMove = new AtomicReference<>(null);

    private ScheduledExecutorService shutdownTarget;

    private ChargeService build() throws Exception {
        props.setPollIntervalMs(50);
        props.setConfirmTimeoutMs(500);
        props.setStaleGraceMs(200);
        props.setMaxDurationMs(60000);
        props.setDockSettleMinMs(100);
        props.setDockSettleMaxMs(2000);
        when(operational.isDecommissioned()).thenReturn(false);
        // 默认无路线：GOAL 直线单发（routePathIds 空列表）
        when(smartNavigation.planRoutePathIds(anyString(), anyDouble(), anyDouble()))
                .thenReturn(java.util.List.of());
        when(smartNavigation.createGoalTask(anyDouble(), anyDouble(), anyDouble()))
                .thenAnswer(inv -> moveTask(MOVE_TASK_ID, moveStatus.get()));
        when(moves.get(MOVE_TASK_ID)).thenAnswer(inv -> moveTask(MOVE_TASK_ID, moveStatus.get()));
        when(moves.active()).thenAnswer(inv -> activeMove.get());
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

    @Test
    void happyPathChargesUntilTargetBattery() throws Exception {
        build();
        stubChargeService(serviceResponse(true, ""));
        telemetryBattery(42.0, false);

        ChargeTaskVO task = service.start(new ChargeService.ChargeCommand("test", null, 1.0, 2.0, 0.5, null));
        assertThat(task.status()).isEqualTo("MOVING");

        // 到桩 → 开始充电服务（data=true）
        moveStatus.set("SUCCEEDED");
        awaitStatus(task.id(), "STARTING");
        // 遥测 charging=true → CHARGING
        telemetryBattery(42.0, true);
        awaitStatus(task.id(), "CHARGING");
        assertThat(service.get(task.id()).batteryAtStart()).isEqualTo(42.0);

        // 充到目标（默认 100）→ SUCCEEDED，留桩不调 data=false
        telemetryBattery(100.0, true);
        ChargeTaskVO done = awaitStatus(task.id(), "SUCCEEDED");
        assertThat(done.message()).contains("目标电量");
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(dispatcher, atLeastOnce()).callService(eq(props.getService()), captor.capture(), anyLong());
        assertThat(captor.getAllValues()).isNotEmpty();
        assertThat(captor.getAllValues()).allSatisfy(args ->
                assertThat(args.path("data").asBoolean()).as("只应发送 data=true").isTrue());
    }

    @Test
    void chargeServiceRejectionFailsTask() throws Exception {
        build();
        stubChargeService(serviceResponse(false, "not docked"));
        telemetryBattery(42.0, false);
        ChargeTaskVO task = service.start(new ChargeService.ChargeCommand("test", null, 1.0, 2.0, 0.5, null));
        moveStatus.set("SUCCEEDED");
        ChargeTaskVO failed = await(() -> {
            ChargeTaskVO vo = service.get(task.id());
            return vo != null && "FAILED".equals(vo.status()) ? vo : null;
        }, 5000);
        assertThat(failed.message()).contains("拒绝开始充电").contains("not docked");
    }

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

    @Test
    void telemetryConfirmTimeoutFailsTask() throws Exception {
        build();
        // 服务成功但遥测一直未确认 charging=true
        stubChargeService(serviceResponse(true, ""));
        telemetryBattery(42.0, false);
        ChargeTaskVO task = service.start(new ChargeService.ChargeCommand("test", null, 1.0, 2.0, 0.5, null));
        moveStatus.set("SUCCEEDED");
        ChargeTaskVO failed = await(() -> {
            ChargeTaskVO vo = service.get(task.id());
            return vo != null && "FAILED".equals(vo.status()) ? vo : null;
        }, 5000);
        assertThat(failed.message()).contains("充电启动未确认");
    }

    @Test
    void chargingInterruptedByNewMoveTaskCancels() throws Exception {
        build();
        stubChargeService(serviceResponse(true, ""));
        telemetryBattery(42.0, false);
        ChargeTaskVO task = service.start(new ChargeService.ChargeCommand("test", null, 1.0, 2.0, 0.5, null));
        moveStatus.set("SUCCEEDED");
        telemetryBattery(42.0, true);
        awaitStatus(task.id(), "CHARGING");
        // 新移动任务出现且 bridge 自动断充（charging=false）
        activeMove.set(moveTask(99L, "EXECUTING"));
        telemetryBattery(42.0, false);
        ChargeTaskVO cancelled = await(() -> {
            ChargeTaskVO vo = service.get(task.id());
            return vo != null && "CANCELLED".equals(vo.status()) ? vo : null;
        }, 5000);
        assertThat(cancelled.message()).contains("打断");
    }

    @Test
    void manualCancelDuringChargingStopsCharging() throws Exception {
        build();
        stubChargeService(serviceResponse(true, ""));
        telemetryBattery(42.0, false);
        ChargeTaskVO task = service.start(new ChargeService.ChargeCommand("test", null, 1.0, 2.0, 0.5, null));
        moveStatus.set("SUCCEEDED");
        telemetryBattery(42.0, true);
        awaitStatus(task.id(), "CHARGING");

        ChargeTaskVO cancelled = service.cancel("手动取消");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        // 取消要尽力断充：set_charging data=false 已下发
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(dispatcher, atLeastOnce()).callService(eq(props.getService()), captor.capture(), anyLong());
        boolean stopSent = captor.getAllValues().stream()
                .anyMatch(args -> !args.path("data").asBoolean(true));
        assertThat(stopSent).as("cancel 应发送 set_charging data=false").isTrue();
    }

    @Test
    void multiPathRouteDispatchesSegmentsUntilDock() throws Exception {
        build();
        stubChargeService(serviceResponse(true, ""));
        telemetryBattery(42.0, false);
        AtomicReference<String> seg1 = new AtomicReference<>("EXECUTING");
        AtomicReference<String> seg2 = new AtomicReference<>("EXECUTING");
        when(smartNavigation.planRoutePathIds(anyString(), anyDouble(), anyDouble()))
                .thenReturn(java.util.List.of(11L, 22L));
        when(smartNavigation.createFollowPathTask(11L)).thenReturn(moveTask(101L, seg1.get()));
        when(smartNavigation.createFollowPathTask(22L)).thenAnswer(inv -> moveTask(202L, seg2.get()));
        when(moves.get(101L)).thenAnswer(inv -> moveTask(101L, seg1.get()));
        when(moves.get(202L)).thenAnswer(inv -> moveTask(202L, seg2.get()));

        ChargeTaskVO task = service.start(new ChargeService.ChargeCommand("test", null, 1.0, 2.0, 0.5, null));
        assertThat(task.status()).isEqualTo("MOVING");
        assertThat(task.internalMoveTaskId()).isEqualTo(101L);

        // 第一段完成 → 接力下发第二段（整条链没走完，绝不调 set_charging）
        seg1.set("SUCCEEDED");
        await(() -> {
            ChargeTaskVO vo = service.get(task.id());
            return vo != null && "MOVING".equals(vo.status())
                    && vo.internalMoveTaskId() != null && vo.internalMoveTaskId() == 202L ? vo : null;
        }, 5000);
        verify(dispatcher, never()).callService(anyString(), any(), anyLong());

        // 第二段完成 → 到桩开始充电 → 充到目标
        seg2.set("SUCCEEDED");
        telemetryBattery(42.0, true);
        awaitStatus(task.id(), "CHARGING");
        telemetryBattery(100.0, true);
        ChargeTaskVO done = awaitStatus(task.id(), "SUCCEEDED");
        assertThat(done.message()).contains("目标电量");
        // 第二段确实下发过
        verify(smartNavigation).createFollowPathTask(22L);
    }

    @Test
    void dockWaitsForOdomQuietBeforeStartCharging() throws Exception {
        build();
        stubChargeService(serviceResponse(true, ""));
        telemetryBattery(42.0, false);
        // /odom 在流但速度非零（末端旋转/减速残余运动）：机器人侧会拒绝运动中的充电请求
        telemetry.setOdomPose(new SimAgvTelemetry.PoseSnapshot(0, 0, 0, 0, Instant.now()));
        telemetry.setOdomVelocity(0.4, 0.0, 0.0);

        ChargeTaskVO task = service.start(new ChargeService.ChargeCommand("test", null, 1.0, 2.0, 0.5, null));
        moveStatus.set("SUCCEEDED");
        awaitStatus(task.id(), "STARTING");

        // 超过最小停稳等待（100ms）但速度非零 → 不得调 set_charging
        TimeUnit.MILLISECONDS.sleep(600);
        verify(dispatcher, never()).callService(anyString(), any(), anyLong());

        // 速度归零（停稳）→ 触发服务并进入充电
        telemetry.setOdomVelocity(0.0, 0.0, 0.0);
        verify(dispatcher, timeout(3000)).callService(eq(props.getService()), any(), anyLong());
        telemetryBattery(42.0, true);
        awaitStatus(task.id(), "CHARGING");
    }

    @Test
    void rescueSkipsNavigationAndChargesDirectly() throws Exception {
        build();
        stubChargeService(serviceResponse(true, ""));
        telemetryBattery(0.0, false); // 电量耗尽死锁场景
        ChargeTaskVO task = service.startRescue();
        // 不经导航：直接进入开始充电阶段
        assertThat(task.status()).isEqualTo("STARTING");
        assertThat(task.source()).isEqualTo("rescue");
        // 停稳等待（odom 不可用 → 纯延时）后调服务 → 遥测确认 → 充电中
        telemetryBattery(1.0, true);
        awaitStatus(task.id(), "CHARGING");
        verify(smartNavigation, never()).createGoalTask(anyDouble(), anyDouble(), anyDouble());
        verify(smartNavigation, never()).createFollowPathTask(anyLong());
        telemetryBattery(100.0, true);
        awaitStatus(task.id(), "SUCCEEDED");
    }

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
}
