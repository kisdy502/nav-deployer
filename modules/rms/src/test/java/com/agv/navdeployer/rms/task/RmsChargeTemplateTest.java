package com.agv.navdeployer.rms.task;

import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.protocol.dto.command.BodyReply;
import com.agv.navdeployer.rms.protocol.dto.command.TaskCommandRequest;
import com.agv.navdeployer.rms.protocol.dto.report.TaskResultEnvelope;
import com.agv.navdeployer.rms.state.RobotStateView;
import com.agv.navdeployer.service.ChargeService;
import com.agv.navdeployer.service.MoveTaskService;
import com.agv.navdeployer.service.RobotOperationalService;
import com.agv.navdeployer.vo.ChargeTaskVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 真机模板机制仿真（移动与业务解耦）：模板校验仅针对 task_type=action 的业务阶段
 * （charge / replace_battery / quality_inspection）——未下发 → 受理成功 + 异步
 * result_report（action_status=4 通用 failed，对齐真机实测，中文详细消息含模板类型）；
 * 已下发 → 充电凭 destination 自主导航、缺省原地充电，质检进入占位执行。
 * navigate 阶段无论是否携带任务流模板都纯移动执行（新架构移动不带模板，旧架构带模板兼容放行）。
 *
 * <p>异步断言说明：终态断言一律用 result_report 的 latch 等待（确定性），
 * 避免 mockito timeout 轮询验证与 worker 线程的 mock 调用互相阻塞。
 */
class RmsChargeTemplateTest {

    private static final String BEHAVIOR_TREE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <root BTCPP_format="4" main_tree_to_execute="ChargeTask">
              <BehaviorTree ID="ChargeTask">
                <Sequence name="charge_task">
                  <Charge relay_on="true"/>
                  <ChargeMonitor limit_percentage="90.0" limit_enable="true"/>
                  <Charge relay_on="false"/>
                </Sequence>
              </BehaviorTree>
            </root>
            """;

    private final ChargeService chargeService = mock(ChargeService.class);

    private RmsTaskService newService(RmsTemplateStore store,
                                      AtomicReference<TaskResultEnvelope> envelope,
                                      CountDownLatch reported) {
        return newService(store, envelope, reported, new RmsProperties());
    }

    /** 带自定义 properties 的变体（如质检占位时长设 0 加速测试）。 */
    private RmsTaskService newService(RmsTemplateStore store,
                                      AtomicReference<TaskResultEnvelope> envelope,
                                      CountDownLatch reported,
                                      RmsProperties properties) {
        RmsResultReporter reporter = new RmsResultReporter(properties);
        reporter.attach((value, id) -> {
            envelope.set(value);
            reported.countDown();
        });
        return new RmsTaskService(new RmsTaskRegistry(), reporter,
                mock(RobotStateView.class), mock(MoveTaskService.class), properties,
                null, null, null, null, null,
                chargeService,
                mock(RobotOperationalService.class),
                null, store);
    }

    private static void shutdown(RmsTaskService service) throws Exception {
        var worker = RmsTaskService.class.getDeclaredField("worker");
        worker.setAccessible(true);
        ((ScheduledExecutorService) worker.get(service)).shutdownNow();
    }

    private static ChargeTaskVO chargeVo(long id, String status) {
        return new ChargeTaskVO(id, "rms:test", status, null, 1.0, 2.0, 0.5,
                100.0, null, null, "", null, LocalDateTime.now(), LocalDateTime.now(), null);
    }

    /**
     * 未下发模板（对齐真机实测报文，双通道）：
     * 1) task/start 同步回包失败：status=2 + template not found 消息；
     * 2) 异步 result_report：action_status=4（通用 failed）+ 中文消息（含模板类型）。
     * 绝不进入充电流程。
     */
    @Test
    void chargeWithoutDownloadedTemplateRejected() throws Exception {
        AtomicReference<TaskResultEnvelope> envelope = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        RmsTaskService service = newService(RmsTemplateStore.inMemory(), envelope, reported);
        try {
            TaskCommandRequest request = new TaskCommandRequest(
                    "charge-no-tpl-1", "1", null, "action", "charge",
                    "back-charge", "241819389364256768", Map.of());
            service.addTask(request);
            BodyReply started = service.startTask(request);
            // 通道1：同步回包失败（真机 status=2 + template not found）
            assertThat(started.status()).isEqualTo(2);
            assertThat(started.msg()).contains("任务模板不存在").contains("类型 charge").contains("back-charge");
            // 通道2：异步 result_report（action_status=4 + 中文详细消息）
            assertThat(reported.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(envelope.get().data().actionStatus()).isEqualTo(4);
            assertThat(envelope.get().data().msg())
                    .contains("任务模板不存在")
                    .contains("类型 charge")
                    .contains("back-charge")
                    .contains("template_id=241819389364256768");
            verify(chargeService, never()).start(any());
            verify(chargeService, never()).startDirect(any(), any());
        } finally {
            shutdown(service);
        }
    }

    /** template_code 缺省时按任务类型名引用模板，同样要求已下发。 */
    @Test
    void chargeWithoutTemplateCodeFallsBackToTypeName() throws Exception {
        AtomicReference<TaskResultEnvelope> envelope = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        RmsTaskService service = newService(RmsTemplateStore.inMemory(), envelope, reported);
        try {
            TaskCommandRequest request = new TaskCommandRequest(
                    "charge-no-tpl-2", "1", null, "action", "charge", null, null, Map.of());
            BodyReply started = service.startTask(request);
            assertThat(started.status()).isEqualTo(2);
            assertThat(reported.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(envelope.get().data().actionStatus()).isEqualTo(4);
            assertThat(envelope.get().data().msg())
                    .contains("任务模板不存在")
                    .contains("类型 charge 的任务模板 charge");
            verify(chargeService, never()).start(any());
        } finally {
            shutdown(service);
        }
    }

    /** 模板已下发：凭任务 destination 自主导航充电，本机无任何充电点配置。 */
    @Test
    void chargeExecutesAfterTemplateDownload() throws Exception {
        RmsTemplateStore store = RmsTemplateStore.inMemory();
        org.mockito.Mockito.when(chargeService.start(any())).thenReturn(chargeVo(8001L, "MOVING"));
        // 内部充电任务 500ms 轮询 → SUCCEEDED → RMS 任务 COMPLETED + result_report
        org.mockito.Mockito.when(chargeService.get(8001L)).thenReturn(chargeVo(8001L, "SUCCEEDED"));
        AtomicReference<TaskResultEnvelope> envelope = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        RmsTaskService service = newService(store, envelope, reported);
        try {
            // 上层先下发模板（行为树原文存档，仿真侧不解析）
            BodyReply added = service.addTemplate(new TaskCommandRequest(
                    "tpl", "0", null, null, "charge", "back-charge", "241819389364256768",
                    Map.of("template_content", BEHAVIOR_TREE_XML,
                            "template_description", "简单充电")));
            assertThat(added.status()).isZero();
            assertThat(store.find("back-charge")).isPresent();
            assertThat(store.find("back-charge").orElseThrow().content())
                    .contains("<Charge relay_on=\"true\"/>");

            TaskCommandRequest request = new TaskCommandRequest(
                    "charge-tpl-1", "1", null, "action", "charge",
                    "back-charge", null,
                    Map.of("destination", Map.of("x", 21.59, "y", 20.52, "yaw", -3.105)));
            BodyReply started = service.startTask(request);
            assertThat(started.status()).as("start reply: %s", started.msg()).isZero();
            // 等 RMS 任务终态（COMPLETED），此时 start 必已被调用，captor 断言无需 timeout 轮询
            assertThat(reported.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(envelope.get().data().actionStatus()).isEqualTo(3);
            var captor = org.mockito.ArgumentCaptor.forClass(ChargeService.ChargeCommand.class);
            verify(chargeService).start(captor.capture());
            ChargeService.ChargeCommand command = captor.getValue();
            assertThat(command.source()).isEqualTo("rms:charge-tpl-1");
            assertThat(command.x()).isEqualTo(21.59);
            assertThat(command.y()).isEqualTo(20.52);
            assertThat(command.yaw()).isEqualTo(-3.105);
        } finally {
            shutdown(service);
        }
    }

    /** 模板已下发但任务未带充电目标点：原地执行充电（导航阶段已就位，真机 Charge 节点语义）。 */
    @Test
    void chargeWithTemplateButNoDestinationChargesInPlace() throws Exception {
        RmsTemplateStore store = RmsTemplateStore.inMemory();
        store.save("back-charge", null, "charge", "简单充电", BEHAVIOR_TREE_XML);
        org.mockito.Mockito.when(chargeService.startDirect(any(), any())).thenReturn(chargeVo(9001L, "MOVING"));
        org.mockito.Mockito.when(chargeService.get(9001L)).thenReturn(chargeVo(9001L, "SUCCEEDED"));
        AtomicReference<TaskResultEnvelope> envelope = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        RmsTaskService service = newService(store, envelope, reported);
        try {
            TaskCommandRequest request = new TaskCommandRequest(
                    "charge-tpl-2", "1", null, "action", "charge",
                    "back-charge", null, Map.of());
            assertThat(service.startTask(request).status()).isZero();
            assertThat(reported.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(envelope.get().data().actionStatus()).isEqualTo(3);
            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(chargeService).startDirect(captor.capture(), any());
            assertThat(captor.getValue()).isEqualTo("rms:charge-tpl-2");
            verify(chargeService, never()).start(any());
        } finally {
            shutdown(service);
        }
    }

    /**
     * 充电任务流指定起点的两阶段流程（移动与业务解耦）：
     * 导航任务（task_type=navigate，携带 charge 任务流模板）<b>不校验模板</b>、只导航
     * （移动任务派发、COMPLETED 上报）；充电由上层后续 start-activity 的充电任务原地执行，
     * 该 action 阶段才校验模板（已下发 → 原地充电完成）。
     */
    @Test
    void navigateStageOfChargeFlowOnlyNavigates() throws Exception {
        RmsTemplateStore store = RmsTemplateStore.inMemory();
        org.mockito.Mockito.when(chargeService.start(any())).thenReturn(chargeVo(8001L, "MOVING"));
        org.mockito.Mockito.when(chargeService.get(8001L)).thenReturn(chargeVo(8001L, "SUCCEEDED"));
        org.mockito.Mockito.when(chargeService.startDirect(any(), any())).thenReturn(chargeVo(8002L, "MOVING"));
        org.mockito.Mockito.when(chargeService.get(8002L)).thenReturn(chargeVo(8002L, "SUCCEEDED"));

        AtomicReference<TaskResultEnvelope> envelope = new AtomicReference<>();
        var reportCount = new java.util.concurrent.atomic.AtomicInteger(0);
        RmsResultReporter reporter = new RmsResultReporter(new RmsProperties());
        reporter.attach((value, id) -> {
            envelope.set(value);
            reportCount.incrementAndGet();
        });
        MoveTaskService moves = mock(MoveTaskService.class);
        com.agv.navdeployer.vo.MoveTaskVO moveVo = new com.agv.navdeployer.vo.MoveTaskVO();
        moveVo.setId(88L);
        moveVo.setTaskType("GOAL");
        var statuses = new java.util.concurrent.atomic.AtomicReference<>("SUCCEEDED");
        org.mockito.Mockito.when(moves.create(any(), any())).thenAnswer(
                inv -> { moveVo.setStatus(statuses.get()); return moveVo; });
        org.mockito.Mockito.when(moves.get(88L)).thenAnswer(inv -> {
            moveVo.setStatus(statuses.get());
            return moveVo;
        });
        RmsTaskService service = new RmsTaskService(new RmsTaskRegistry(), reporter,
                mock(RobotStateView.class), moves, new RmsProperties(),
                null, null, null, null,
                new com.agv.navdeployer.service.SmartNavigationService(moves, null, null, null, null),
                chargeService,
                mock(RobotOperationalService.class),
                null, store);
        try {
            // 阶段1：navigate（模板类型是 charge——旧架构兼容，模板未下发也不拦）→ 只导航
            TaskCommandRequest navigate = new TaskCommandRequest(
                    "charge-flow-1", "1", null, "navigate", "charge",
                    "back-charge", null,
                    Map.of("destination", Map.of("x", 21.59, "y", 20.52, "yaw", -3.105)));
            assertThat(service.startTask(navigate).status()).isZero();
            awaitReport(reportCount, 1);
            assertThat(envelope.get().data().actionStatus()).isEqualTo(3);
            verify(moves).create(any(), any());
            verify(chargeService, never()).start(any());
            verify(chargeService, never()).startDirect(any(), any());

            // 阶段2：上层确认导航完成后发 start-activity 充电任务（不带 destination）；
            // 此 action 阶段才校验模板——先下发再执行 → 原地充电
            store.save("back-charge", null, "charge", "简单充电", BEHAVIOR_TREE_XML);
            envelope.set(null);
            TaskCommandRequest charge = new TaskCommandRequest(
                    "charge-flow-1", "2", null, "action", "charge",
                    "back-charge", null, Map.of());
            assertThat(service.startTask(charge).status()).isZero();
            awaitReport(reportCount, 2);
            assertThat(envelope.get().data().actionStatus()).isEqualTo(3);
            verify(chargeService).startDirect(
                    org.mockito.ArgumentMatchers.eq("rms:charge-flow-1"), any());
        } finally {
            shutdown(service);
        }
    }

    /** 等待 result_report 计数到达 expected（10s 截止，20ms 轮询）。 */
    private static void awaitReport(java.util.concurrent.atomic.AtomicInteger counter, int expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (counter.get() >= expected) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }
        throw new AssertionError("result_report count " + counter.get() + " < " + expected + " within 10s");
    }

    /** 按名匹配语义（业务要求）：名字即可——后缀/文件名不参与匹配；首尾空格容错；大小写敏感。 */
    @Test
    void templateMatchedByNameOnlyTrimmedAndCaseSensitive() {
        RmsTemplateStore store = RmsTemplateStore.inMemory();
        store.save(" back-inspect ", null, "quality_inspection", "线束质检", INSPECTION_TREE_XML);
        // 名字即可：去空格后命中（下发报文带冗余空白不影响匹配）
        assertThat(store.find("back-inspect")).isPresent();
        assertThat(store.find(" back-inspect ")).isPresent();
        // 大小写敏感：不同大小写不算同一模板
        assertThat(store.find("Back-Inspect")).isEmpty();
        // 后缀无关：模板按 code 匹配，落盘文件才叫 back-inspect.json——引用不得拼后缀
        assertThat(store.find("back-inspect.json")).isEmpty();
    }

    /**
     * 原始模板文件（直接放入目录的行为树 XML 等）按文件名去后缀注册：
     * 任务只带名字（LAD-28-A-not-lift）即可命中，无需 task_template/add、无需后缀。
     */
    @Test
    void rawTemplateFileRegisteredByBaseName(@TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("LAD-28-A-not-lift.xml"), INSPECTION_TREE_XML);
        RmsTemplateStore store = new RmsTemplateStore(dir);
        assertThat(store.find("LAD-28-A-not-lift")).as("按文件名（去后缀）应命中").isPresent();
        assertThat(store.find("LAD-28-A-not-lift").orElseThrow().content())
                .contains("InspectionTask");
        // 名字即可：带后缀引用不命中（后缀不参与匹配），大小写敏感
        assertThat(store.find("LAD-28-A-not-lift.xml")).isEmpty();
        assertThat(store.find("lad-28-a-not-lift")).isEmpty();
    }

    /** 模板落盘持久化：重启（新 store 实例同目录）后模板仍在，删除后消失。 */
    @Test
    void templatePersistsAcrossRestart(@TempDir Path dir) {
        RmsTemplateStore first = new RmsTemplateStore(dir);
        first.save("back-charge", "tpl-1", "charge", "简单充电", BEHAVIOR_TREE_XML);

        RmsTemplateStore reloaded = new RmsTemplateStore(dir);
        assertThat(reloaded.find("back-charge")).isPresent();
        RmsTemplateStore.Template template = reloaded.find("back-charge").orElseThrow();
        assertThat(template.type()).isEqualTo("charge");
        assertThat(template.content()).contains("ChargeTask");

        assertThat(reloaded.delete("back-charge")).isTrue();
        assertThat(new RmsTemplateStore(dir).find("back-charge")).isEmpty();
    }

    // ==================== 质检任务模板校验（与充电同口径） ====================

    private static final String INSPECTION_TREE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <root BTCPP_format="4" main_tree_to_execute="InspectionTask">
              <BehaviorTree ID="InspectionTask">
                <Sequence name="inspection_task">
                  <MoveArm pose="inspect"/>
                  <Inspect result="OK"/>
                  <MoveArm pose="home"/>
                </Sequence>
              </BehaviorTree>
            </root>
            """;

    /**
     * 质检模板未下发（双通道，对齐充电）：
     * 1) task/start 同步回包失败：status=2 + 中文详细消息（含模板类型/编码/template_id）；
     * 2) 异步 result_report：action_status=4。绝不进入质检占位执行（不会上报 action_status=3）。
     */
    @Test
    void qualityInspectionWithoutDownloadedTemplateRejected() throws Exception {
        AtomicReference<TaskResultEnvelope> envelope = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        RmsTaskService service = newService(RmsTemplateStore.inMemory(), envelope, reported);
        try {
            TaskCommandRequest request = new TaskCommandRequest(
                    "qi-no-tpl-1", "1", null, "action", "quality_inspection",
                    "back-inspect", "241819389364256999", Map.of());
            service.addTask(request);
            BodyReply started = service.startTask(request);
            assertThat(started.status()).isEqualTo(2);
            assertThat(started.msg()).contains("任务模板不存在")
                    .contains("类型 quality_inspection").contains("back-inspect");
            assertThat(reported.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(envelope.get().data().actionStatus()).isEqualTo(4);
            assertThat(envelope.get().data().msg())
                    .contains("任务模板不存在")
                    .contains("类型 quality_inspection")
                    .contains("back-inspect")
                    .contains("template_id=241819389364256999");
        } finally {
            shutdown(service);
        }
    }

    /** 质检模板已下发：占位执行正常完成（action_status=3），不再被模板校验拦截。 */
    @Test
    void qualityInspectionExecutesAfterTemplateDownload() throws Exception {
        RmsTemplateStore store = RmsTemplateStore.inMemory();
        store.save("back-inspect", "tpl-qi-1", "quality_inspection", "线束质检", INSPECTION_TREE_XML);
        RmsProperties properties = new RmsProperties();
        properties.getBehavior().setInspectionDurationMs(0);
        AtomicReference<TaskResultEnvelope> envelope = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        RmsTaskService service = newService(store, envelope, reported, properties);
        try {
            TaskCommandRequest request = new TaskCommandRequest(
                    "qi-tpl-1", "1", null, "action", "quality_inspection",
                    "back-inspect", null, Map.of());
            BodyReply started = service.startTask(request);
            assertThat(started.status()).as("start reply: %s", started.msg()).isZero();
            assertThat(reported.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(envelope.get().data().actionStatus()).isEqualTo(3);
        } finally {
            shutdown(service);
        }
    }

    /**
     * 质检任务流（移动与业务解耦，用户调度实际形态 navigate×N + start-activity action）：
     * - navigate 阶段携带任务流模板但<b>不校验</b>：模板未下发也纯移动完成（COMPLETED）；
     * - action 阶段才校验：未下发 → 同步 status=2 + 异步 action_status=4，不派发业务动作；
     *   下发后重发同任务 → 占位质检完成（COMPLETED）。
     */
    @Test
    void navigateStageOfQualityFlowSkipsTemplateCheck() throws Exception {
        RmsTemplateStore store = RmsTemplateStore.inMemory();

        AtomicReference<TaskResultEnvelope> envelope = new AtomicReference<>();
        var reportCount = new java.util.concurrent.atomic.AtomicInteger(0);
        RmsResultReporter reporter = new RmsResultReporter(new RmsProperties());
        reporter.attach((value, id) -> {
            envelope.set(value);
            reportCount.incrementAndGet();
        });
        MoveTaskService moves = mock(MoveTaskService.class);
        com.agv.navdeployer.vo.MoveTaskVO moveVo = new com.agv.navdeployer.vo.MoveTaskVO();
        moveVo.setId(66L);
        moveVo.setTaskType("GOAL");
        var statuses = new java.util.concurrent.atomic.AtomicReference<>("SUCCEEDED");
        org.mockito.Mockito.when(moves.create(any(), any())).thenAnswer(
                inv -> { moveVo.setStatus(statuses.get()); return moveVo; });
        org.mockito.Mockito.when(moves.get(66L)).thenAnswer(inv -> {
            moveVo.setStatus(statuses.get());
            return moveVo;
        });
        RmsProperties properties = new RmsProperties();
        properties.getBehavior().setInspectionDurationMs(0);
        RmsTaskService service = new RmsTaskService(new RmsTaskRegistry(), reporter,
                mock(RobotStateView.class), moves, properties,
                null, null, null, null,
                new com.agv.navdeployer.service.SmartNavigationService(moves, null, null, null, null),
                chargeService,
                mock(RobotOperationalService.class),
                null, store);
        try {
            // 阶段1：navigate（模板未下发）→ 纯移动，COMPLETED
            TaskCommandRequest navigate = new TaskCommandRequest(
                    "qi-flow-1", "1", null, "navigate", "quality_inspection",
                    "back-inspect", null,
                    Map.of("destination", Map.of("x", 32.09, "y", -5.07, "yaw", 0.0)));
            assertThat(service.startTask(navigate).status())
                    .as("navigate 不校验模板，应放行").isZero();
            awaitReport(reportCount, 1);
            assertThat(envelope.get().data().actionStatus()).isEqualTo(3);
            verify(moves).create(any(), any());

            // 阶段2：action 质检（模板仍未下发）→ 双通道拒绝，不执行业务
            envelope.set(null);
            TaskCommandRequest inspect = new TaskCommandRequest(
                    "qi-flow-1", "2", null, "action", "quality_inspection",
                    "back-inspect", null, Map.of());
            BodyReply rejected = service.startTask(inspect);
            assertThat(rejected.status()).isEqualTo(2);
            assertThat(rejected.msg()).contains("类型 quality_inspection").contains("back-inspect");
            awaitReport(reportCount, 2);
            assertThat(envelope.get().data().actionStatus()).isEqualTo(4);
            verify(moves, times(1)).create(any(), any());

            // 阶段3：下发模板后重发同任务 → 占位质检完成
            store.save("back-inspect", null, "quality_inspection", "线束质检", INSPECTION_TREE_XML);
            envelope.set(null);
            assertThat(service.startTask(inspect).status()).isZero();
            awaitReport(reportCount, 3);
            assertThat(envelope.get().data().actionStatus()).isEqualTo(3);
            verify(moves, times(1)).create(any(), any());
        } finally {
            shutdown(service);
        }
    }
}
