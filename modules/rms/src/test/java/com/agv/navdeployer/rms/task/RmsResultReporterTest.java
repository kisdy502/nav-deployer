package com.agv.navdeployer.rms.task;

import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.protocol.dto.command.TaskCommandRequest;
import com.agv.navdeployer.rms.protocol.dto.report.TaskResultEnvelope;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RmsResultReporterTest {

    @Test
    void retriesFailedTemplateResultBeforeNextResult() {
        RmsResultReporter reporter = new RmsResultReporter(new RmsProperties());
        java.util.List<TaskResultEnvelope> published = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean unavailable =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        reporter.attach((envelope, id) -> {
            if (unavailable.get()) {
                throw new IllegalStateException("disconnected");
            }
            published.add(envelope);
        });
        RmsTask missingTemplate = new RmsTask(new TaskCommandRequest(
                "missing-template", "action-1", null, "action", "charge",
                "back-charge", null, Map.of()));
        missingTemplate.setStatus(RmsTaskStatus.FAILED);
        missingTemplate.setMessage("任务模板不存在");
        reporter.report(missingTemplate);
        assertThat(published).isEmpty();

        unavailable.set(false);
        RmsTask next = new RmsTask(new TaskCommandRequest(
                "next", "action-2", null, "action", "charge",
                "back-charge", null, Map.of()));
        next.setStatus(RmsTaskStatus.COMPLETED);
        reporter.report(next);

        assertThat(published).extracting(value -> value.data().taskId())
                .containsExactly("missing-template", "next");
        assertThat(published.getFirst().data().actionStatus()).isEqualTo(4);
        assertThat(published.getFirst().data().actionId()).isEqualTo("action-1");
        assertThat(published.getFirst().data().msg()).isEqualTo("任务模板不存在");
    }

    /** 质检任务完成上报携带业务载荷（NG 结果 + 线束参数 + points 点位明细），形状与真机报文一致。 */
    @Test
    void reportsCompletedInspectionWithNgBusinessResultAndRealMachineShape() {
        RmsProperties properties = new RmsProperties();
        properties.getBehavior().setInspectionResult("NG");
        RmsResultReporter reporter = new RmsResultReporter(properties);
        AtomicReference<TaskResultEnvelope> published = new AtomicReference<>();
        reporter.attach((envelope, taskId) -> published.set(envelope));

        RmsTask task = new RmsTask(new TaskCommandRequest(
                "task-1", "action-1", null, "action", "quality_inspection",
                null, null, Map.of("wireharness_type", "LAD-52010021")));
        task.setStatus(RmsTaskStatus.COMPLETED);
        reporter.report(task);

        TaskResultEnvelope.TaskResultData data = published.get().data();
        assertThat(data.actionStatus()).isEqualTo(3);
        assertThat(data.msg()).isEqualTo("task successed");
        assertThat(data.resultReport()).containsEntry("inspection_result", "NG");
        assertThat(data.resultReport()).containsEntry("task_id", "task-1");
        assertThat(data.resultReport()).containsEntry("wireharness_type", "LAD-52010021");
        assertThat((java.util.List<?>) data.resultReport().get("points")).hasSize(2);
    }

    /** navigate 任务即使带 quality_inspection 模板，完成上报也不携带质检业务载荷。 */
    @Test
    void reportsCompletedNavigationWithoutInspectionPayload() {
        RmsResultReporter reporter = new RmsResultReporter(new RmsProperties());
        AtomicReference<TaskResultEnvelope> published = new AtomicReference<>();
        reporter.attach((envelope, taskId) -> published.set(envelope));

        RmsTask task = new RmsTask(new TaskCommandRequest(
                "nav-1", "action-nav-1", null, "navigate", "quality_inspection",
                null, null, Map.of("destination", Map.of("x", 1.0, "y", 2.0))));
        task.setStatus(RmsTaskStatus.COMPLETED);
        reporter.report(task);

        TaskResultEnvelope.TaskResultData data = published.get().data();
        assertThat(data.actionStatus()).isEqualTo(3);
        assertThat(data.msg()).isEqualTo("task successed");
        assertThat(data.resultReport()).isNull();
    }

    /** AGV 节点失败上报保留错误码 13 与原始可读失败原因，不带业务载荷。 */
    @Test
    void preservesAgvNodeFailureCodeAndReadableReason() {
        RmsResultReporter reporter = new RmsResultReporter(new RmsProperties());
        AtomicReference<TaskResultEnvelope> published = new AtomicReference<>();
        reporter.attach((envelope, taskId) -> published.set(envelope));

        RmsTask task = new RmsTask(new TaskCommandRequest(
                "nav-2", "action-nav-2", null, "action", "rmf_navigate",
                null, null, Map.of()));
        task.setMessage("navigation timeout");
        task.setStatus(RmsTaskStatus.AGV_NODE_FAILED);
        reporter.report(task);

        TaskResultEnvelope.TaskResultData data = published.get().data();
        assertThat(data.actionStatus()).isEqualTo(13);
        assertThat(data.msg()).isEqualTo("navigation timeout");
        assertThat(data.resultReport()).isNull();
    }

    /** 质检执行失败（拍照节点）上报错误码 10 与失败原因，不发布业务质检结果。 */
    @Test
    void doesNotPublishBusinessInspectionResultWhenInspectionExecutionFailed() {
        RmsResultReporter reporter = new RmsResultReporter(new RmsProperties());
        AtomicReference<TaskResultEnvelope> published = new AtomicReference<>();
        reporter.attach((envelope, taskId) -> published.set(envelope));

        RmsTask task = new RmsTask(new TaskCommandRequest(
                "inspection-2", "action-inspection-2", null, "action", "quality_inspection",
                null, null, Map.of()));
        task.setMessage("camera unavailable");
        task.setStatus(RmsTaskStatus.PHOTO_NODE_FAILED);
        reporter.report(task);

        TaskResultEnvelope.TaskResultData data = published.get().data();
        assertThat(data.actionStatus()).isEqualTo(10);
        assertThat(data.msg()).isEqualTo("camera unavailable");
        assertThat(data.resultReport()).isNull();
    }

    /** 全量校验 RmsTaskStatus 到 action_status 的映射与真机机身枚举一一对应。 */
    @Test
    void alignsEveryActionStatusCodeWithRobotBodyEnum() {
        assertThat(RmsTaskStatus.IDLE.actionStatusCode()).isZero();
        assertThat(RmsTaskStatus.RUNNING.actionStatusCode()).isEqualTo(1);
        assertThat(RmsTaskStatus.PAUSED.actionStatusCode()).isEqualTo(2);
        assertThat(RmsTaskStatus.COMPLETED.actionStatusCode()).isEqualTo(3);
        assertThat(RmsTaskStatus.FAILED.actionStatusCode()).isEqualTo(4);
        assertThat(RmsTaskStatus.CANCELED.actionStatusCode()).isEqualTo(5);
        assertThat(RmsTaskStatus.BT_LOAD_FAILED.actionStatusCode()).isEqualTo(6);
        assertThat(RmsTaskStatus.PHOTO_NODE_FAILED.actionStatusCode()).isEqualTo(10);
        assertThat(RmsTaskStatus.ARM_NODE_FAILED.actionStatusCode()).isEqualTo(11);
        assertThat(RmsTaskStatus.RESULT_UPLOAD_FAILED.actionStatusCode()).isEqualTo(12);
        assertThat(RmsTaskStatus.AGV_NODE_FAILED.actionStatusCode()).isEqualTo(13);
        assertThat(RmsTaskStatus.AUDIO_NODE_FAILED.actionStatusCode()).isEqualTo(14);
        assertThat(RmsTaskStatus.LIFT_POLE_NODE_FAILED.actionStatusCode()).isEqualTo(15);
        assertThat(RmsTaskStatus.LLM_NODE_FAILED.actionStatusCode()).isEqualTo(16);
    }
}
