package com.agv.navdeployer.rms.task;

import com.agv.navdeployer.rms.protocol.dto.report.TaskResultEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 任务结果上报出口：RmsRobotSession 建好通道后 attach，
 * 未就绪时丢弃并记日志（重连窗口内 RMS 依赖轮询 task/status 兜底）。
 * 独立成组件是为了斩断 service ↔ session 的构造环。
 */
@Component
public class RmsResultReporter {

    private static final Logger log = LoggerFactory.getLogger(RmsResultReporter.class);

    public interface Sink {
        void publish(TaskResultEnvelope envelope, String taskId) throws Exception;
    }

    private volatile Sink sink;

    public void attach(Sink sink) {
        this.sink = sink;
    }

    public void detach() {
        this.sink = null;
    }

    public void report(RmsTask task) {
        TaskResultEnvelope envelope = TaskResultEnvelope.of(new TaskResultEnvelope.TaskResultData(
                task.taskId(),
                task.actionId(),
                task.taskType(),
                task.status().actionStatusCode(),
                task.status() == RmsTaskStatus.FAILED ? "task failed!" : "task success!",
                task.isTemplate("quality_inspection") ? simulatedInspectionResult(task) : null
        ));
        Sink current = sink;
        if (current == null) {
            log.warn("rms result sink not ready, drop result_report task_id={} status={}",
                    task.taskId(), task.status());
            return;
        }
        try {
            current.publish(envelope, task.taskId());
        } catch (Exception exception) {
            log.warn("failed to publish result_report task_id={} msg={}",
                    task.taskId(), exception.getMessage());
        }
    }

    /** 质检占位结果（仿真机械臂接入前的模拟数据结构，对齐 mock 的 result_report 字段）。 */
    private java.util.Map<String, Object> simulatedInspectionResult(RmsTask task) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("task_id", task.taskId());
        result.put("inspection_result", "PENDING_SIMULATED_ARM");
        result.put("note", "仿真机械臂未接入，占位结果");
        return result;
    }
}
