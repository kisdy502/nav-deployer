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
import static org.mockito.Mockito.verify;

/**
 * 真机模板机制仿真：充电任务引用的模板必须已由上层 task_template/add 下发并存储，
 * 未下发 → 受理成功 + 异步 result_report（action_status=4 通用 failed，对齐真机实测，
 * 消息为中文详细版，含模板类型）；已下发 → 携带 destination 时自主导航充电，
 * 未携带时原地充电（导航阶段已就位）；充电任务流的起点导航（task_type=navigate）
 * 只导航、完成上报，不触发充电。
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
        RmsResultReporter reporter = new RmsResultReporter(new RmsProperties());
        reporter.attach((value, id) -> {
            envelope.set(value);
            reported.countDown();
        });
        return new RmsTaskService(new RmsTaskRegistry(), reporter,
                mock(RobotStateView.class), mock(MoveTaskService.class), new RmsProperties(),
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
     * 充电任务流指定起点的两阶段流程：导航任务（task_type=navigate）即使携带
     * charge 模板类型也只导航（移动任务派发、COMPLETED 上报），绝不触发充电；
     * 充电由上层后续 start-activity 的充电任务原地执行。
     */
    @Test
    void navigateStageOfChargeFlowOnlyNavigates() throws Exception {
        RmsTemplateStore store = RmsTemplateStore.inMemory();
        store.save("back-charge", null, "charge", "简单充电", BEHAVIOR_TREE_XML);
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
            // 阶段1：navigate（模板类型是 charge——任务流模板）→ 只导航，完成上报 COMPLETED
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

            // 阶段2：上层确认导航完成后发 start-activity 充电任务（不带 destination）→ 原地充电
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
}
