package com.agv.navdeployer.rms.task;

import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.inspection.RmsInspectionResultFactory;
import com.agv.navdeployer.rms.protocol.dto.report.TaskResultEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 任务结果上报出口：RmsRobotSession 建好通道后 attach，
 * 未就绪/发送失败时进入待发队列（上限 {@value #PENDING_MAX} 条），
 * attach 成功后按序重放——防止 zenoh 重连窗口内 result_report 丢失导致
 * 云端只能靠轮询收尾、质检分支拿不到 inspection_result。
 * 独立成组件是为了斩断 service ↔ session 的构造环。
 */
@Component
public class RmsResultReporter {

    private static final Logger log = LoggerFactory.getLogger(RmsResultReporter.class);
    private static final int PENDING_MAX = 100;
    private final RmsProperties properties;

    public interface Sink {
        void publish(TaskResultEnvelope envelope, String taskId) throws Exception;
    }

    private record Pending(TaskResultEnvelope envelope, String taskId) {
    }

    private volatile Sink sink;
    /** attach 与 report 并发访问，重放需按序；容量上限防内存膨胀 */
    private final Deque<Pending> pending = new ArrayDeque<>();

    public RmsResultReporter(RmsProperties properties) {
        this.properties = properties;
    }

    public synchronized void attach(Sink sink) {
        this.sink = sink;
        replayPending();
    }

    public synchronized void detach() {
        this.sink = null;
    }

    public synchronized void report(RmsTask task) {
        TaskResultEnvelope envelope = TaskResultEnvelope.of(new TaskResultEnvelope.TaskResultData(
                task.taskId(),
                task.actionId(),
                task.taskType(),
                task.status().actionStatusCode(),
                resultMessage(task),
                task.isInspectionAction() && task.status() == RmsTaskStatus.COMPLETED
                        ? inspectionResult(task) : null
        ));
        Sink current = sink;
        if (current == null) {
            enqueue(envelope, task.taskId());
            log.warn("rms result sink not ready, result_report 已入待发队列 task_id={} status={} pending={}",
                    task.taskId(), task.status(), pending.size());
            return;
        }
        try {
            current.publish(envelope, task.taskId());
        } catch (Exception exception) {
            // 通道异常：入队等 attach/下次上报时重放
            enqueue(envelope, task.taskId());
            log.warn("failed to publish result_report, 已入待发队列 task_id={} pending={} msg={}",
                    task.taskId(), pending.size(), exception.getMessage());
        }
    }

    /** 通道就绪后重放积压；单条失败即停（通道大概率又断了，剩余留待下次） */
    private void replayPending() {
        int replayed = 0;
        while (!pending.isEmpty() && sink != null) {
            Pending head = pending.peek();
            try {
                sink.publish(head.envelope(), head.taskId());
                pending.poll();
                replayed++;
            } catch (Exception exception) {
                log.warn("replay result_report deferred, task_id={} remaining={} msg={}",
                        head.taskId(), pending.size(), exception.getMessage());
                return;
            }
        }
        if (replayed > 0) {
            log.info("replayed {} pending result_report(s), remaining={}", replayed, pending.size());
        }
    }

    private void enqueue(TaskResultEnvelope envelope, String taskId) {
        if (pending.size() >= PENDING_MAX) {
            Pending dropped = pending.pollFirst();
            log.error("pending result_report overflow, drop oldest task_id={}", dropped.taskId());
        }
        pending.addLast(new Pending(envelope, taskId));
    }

    private java.util.Map<String, Object> inspectionResult(RmsTask task) {
        return RmsInspectionResultFactory.create(
                task.taskId(), properties.getBehavior().getInspectionResult(), task.parameters());
    }

    private static String resultMessage(RmsTask task) {
        if (task.message() != null && !task.message().isBlank()) {
            return task.message();
        }
        if (task.status() == RmsTaskStatus.COMPLETED) {
            return "task successed";
        }
        if (task.status() == RmsTaskStatus.CANCELED) {
            return "task cancelled";
        }
        return task.status().failed() ? task.status().wireName() : "task finished";
    }
}
