package com.agv.navdeployer.rms.protocol.keys;

/**
 * 【A 模式：robot 单向发布，RMS 订阅】的 key 集合。
 * 本类里的每个 key 都只配合 ZenohChannel.putOnce 使用——发出去不等回复。
 */
public final class RmsReportKeys {

    private final RobotKeyContext ctx;

    public RmsReportKeys(String prefix, String robotType, String robotCode) {
        this.ctx = new RobotKeyContext(prefix, robotType, robotCode);
    }

    /** body 状态上报：.../api/v1/status/report（状态快照，5s 周期）。 */
    public String bodyStatusReport() {
        return ctx.bodyApiBase() + "/status/report";
    }

    /** legacy 状态上报：{prefix}/robot/{code}/status/report（与 body 通道并存的历史兼容）。 */
    public String legacyStatusReport() {
        return ctx.robotScopedBase() + "/status/report";
    }

    /** legacy 心跳 put：{prefix}/robot/{code}/heartbeat（心跳 query 的发布伴生通道）。 */
    public String legacyHeartbeat() {
        return ctx.robotScopedBase() + "/heartbeat";
    }

    /** 任务结果上报：.../api/v1/task/{task_id}/result_report（任务终态时一次）。 */
    public String taskResultReport(String taskId) {
        return ctx.bodyApiBase() + "/task/result_report";
    }
}
