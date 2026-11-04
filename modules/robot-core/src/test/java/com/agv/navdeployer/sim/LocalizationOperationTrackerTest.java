package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定位操作跟踪器：话题接收合并 / 终态不可回退 / 终态等待 / 迟到回调丢弃。
 */
class LocalizationOperationTrackerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private LocalizationOperationTracker tracker() {
        SimAgvProperties props = new SimAgvProperties();
        return new LocalizationOperationTracker(props);
    }

    private LocalizationOperation op(String id, String state, boolean finished, String stage) {
        return new LocalizationOperation(id, "LOAD_MAP", "map0922", state, stage,
                finished, "SUCCEEDED".equals(state), "msg", true, false, 1.0,
                java.time.Instant.now());
    }

    /** RUNNING→SUCCEEDED 正常流转：终态后 hasRunningOperation 清零并更新 latest */
    @Test
    void runningThenSucceeded() {
        LocalizationOperationTracker tracker = tracker();
        tracker.ingest(op("op-1", "RUNNING", false, "STOPPING_LOCALIZATION"));
        assertTrue(tracker.hasRunningOperation());
        assertEquals("RUNNING", tracker.get("op-1").state());

        tracker.ingest(op("op-1", "SUCCEEDED", true, "FINISHED"));
        assertFalse(tracker.hasRunningOperation());
        assertEquals("SUCCEEDED", tracker.get("op-1").state());
        assertEquals("op-1", tracker.latest().operationId());
    }

    /** 已到终态（TIMED_OUT）的记录不被迟到的 RUNNING 回调回退 */
    @Test
    void terminalRecordNotRevertedByLateRunningCallback() {
        LocalizationOperationTracker tracker = tracker();
        tracker.ingest(op("op-1", "TIMED_OUT", true, "WAITING_LOCALIZATION"));
        tracker.ingest(op("op-1", "RUNNING", false, "STOPPING_LOCALIZATION"));
        assertEquals("TIMED_OUT", tracker.get("op-1").state());
    }

    /** awaitTerminal 挂起等待，终态（FAILED）到达时完成并返回该终态记录 */
    @Test
    void awaitTerminalCompletesWhenFinishedArrives() throws Exception {
        LocalizationOperationTracker tracker = tracker();
        tracker.ingest(op("op-2", "RUNNING", false, "STARTING_LOCALIZATION"));
        CompletableFuture<LocalizationOperation> future = tracker.awaitTerminal("op-2", 5000);
        assertFalse(future.isDone());
        tracker.ingest(op("op-2", "FAILED", true, "STARTING_LOCALIZATION"));
        LocalizationOperation terminal = future.get(1, TimeUnit.SECONDS);
        assertEquals("FAILED", terminal.state());
    }

    /** 操作已处于终态时 awaitTerminal 立即完成，无需等待 */
    @Test
    void awaitTerminalImmediateWhenAlreadyFinished() throws Exception {
        LocalizationOperationTracker tracker = tracker();
        tracker.ingest(op("op-3", "SUCCEEDED", true, "FINISHED"));
        LocalizationOperation terminal = tracker.awaitTerminal("op-3", 100).get(1, TimeUnit.SECONDS);
        assertEquals("SUCCEEDED", terminal.state());
    }

    /** 超时时间内无终态到达时 awaitTerminal 以异常完成（ExecutionException） */
    @Test
    void awaitTerminalTimesOutWhenNoTerminalArrives() {
        LocalizationOperationTracker tracker = tracker();
        tracker.ingest(op("op-4", "RUNNING", false, "ACCEPTED"));
        CompletableFuture<LocalizationOperation> future = tracker.awaitTerminal("op-4", 50);
        org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS));
    }

    /** 只消费配置话题的消息：其他话题与缺少 operation_id 的旧版消息均忽略 */
    @Test
    void publishesForConfiguredTopicOnly() throws Exception {
        LocalizationOperationTracker tracker = tracker();
        String frame = "{\"op\":\"publish\",\"topic\":\"/agv/localization_operation\",\"msg\":{"
                + "\"operation_id\":\"op-5\",\"operation_type\":\"RELOCALIZE\",\"map_name\":\"map0922\","
                + "\"state\":\"RUNNING\",\"stage\":\"WAITING_LOCALIZATION\",\"finished\":false,"
                + "\"success\":false,\"message\":\"等待\",\"pose_initialized\":true,"
                + "\"navigation_ready\":false,\"elapsed_sec\":2.5}}";
        tracker.onMessage(mapper.readTree(frame));
        assertEquals("RUNNING", tracker.get("op-5").state());
        assertEquals("RELOCALIZE", tracker.get("op-5").operationType());
        assertEquals(2.5, tracker.get("op-5").elapsedSec(), 1e-9);

        // 其他话题不消费
        tracker.onMessage(mapper.readTree(
                "{\"op\":\"publish\",\"topic\":\"/agv/status\",\"msg\":{\"operation_id\":\"op-6\"}}"));
        assertNull(tracker.get("op-6"));
        // 旧版机器人无 operation_id 的消息：忽略
        tracker.onMessage(mapper.readTree(
                "{\"op\":\"publish\",\"topic\":\"/agv/localization_operation\",\"msg\":{\"state\":\"RUNNING\"}}"));
        assertNull(tracker.get(""));
    }

    /** TIMED_OUT 终态的 failureReason 归一化后仍含原始状态标识，未上报的可选字段为 null */
    @Test
    void failureReasonNormalizesTimeout() {
        LocalizationOperation timedOut = new LocalizationOperation("op-7", "LOAD_MAP", "m",
                "TIMED_OUT", "WAITING_LOCALIZATION", true, false, "", null, null, 600,
                java.time.Instant.now());
        assertTrue(timedOut.failureReason().contains("TIMED_OUT"));
        assertNull(timedOut.poseInitialized());
    }
}
