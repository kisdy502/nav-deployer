package com.agv.navdeployer.rms.task;

import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.inspection.RmsInspectionResultFactory;
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
    private final RmsProperties properties;

    public interface Sink {
        void publish(TaskResultEnvelope envelope, String taskId) throws Exception;
    }

    private volatile Sink sink;

    public RmsResultReporter(RmsProperties properties) {
        this.properties = properties;
    }

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
                resultMessage(task),
                task.isInspectionAction() && task.status() == RmsTaskStatus.COMPLETED
                        ? inspectionResult(task) : null
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
