package com.agv.navdeployer.rms.state;

import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.protocol.dto.command.BodyStatusData;
import com.agv.navdeployer.rms.protocol.dto.report.RobotStatusReport;
import com.agv.navdeployer.rms.protocol.dto.service.HeartbeatPayload;
import com.agv.navdeployer.rms.protocol.dto.report.StatusReportEnvelope;
import com.agv.navdeployer.rms.protocol.dto.report.BodyFullStatusData;
import com.agv.navdeployer.rms.task.RmsTask;
import com.agv.navdeployer.rms.task.RmsTaskRegistry;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RMS 视角的机器人状态唯一出口（防烂铁律②：其他层不许直接读遥测拼状态）。
 * 聚合 SimAgvTelemetry（位姿/电量/模式）+ RmsTaskRegistry（在途任务）+ 配置。
 */
@Component
public class RobotStateView {

    /** 仿真双电池包共用 ID（对齐真机报文样式） */
    private static final String SIM_BATTERY_ID = "313536302D363033313";
    /** 满电容量 mAh（capacity 按百分比折算） */
    private static final double FULL_CAPACITY_MAH = 40000.0;
    /** 电压线性模型 mV：45000 + 120*pct（21% ≈ 47.5V，满电 ≈ 57V） */
    private static double voltageMv(double pct) {
        return 45000.0 + 120.0 * pct;
    }

    /** 开机自检静态报告（真机格式，RMS 不解析、原样透传） */
    private static final Map<String, Object> INSPECTION_REPORT = Map.ofEntries(
            Map.entry("end_time_ms", 0),
            Map.entry("format", "inspection_report_v2"),
            Map.entry("framework_version", "1.0.0"),
            Map.entry("inspection_id", ""),
            Map.entry("modules", List.of()),
            Map.entry("overall_message", "No modules configured"),
            Map.entry("overall_result", "pass"),
            Map.entry("schema_version", "1.0"),
            Map.entry("start_time_ms", 0),
            Map.entry("trigger_type", "power_on"),
            Map.entry("total_duration_ms", 0),
            Map.entry("version", 1));

    private final SimAgvTelemetry telemetry;
    private final RmsTaskRegistry taskRegistry;
    private final RmsProperties props;
    private final HostStats hostStats;

    /** RMS 分配的 robot_code（注册后由 Session 设置；null=用配置值） */
    private volatile String effectiveRobotCode;

    public RobotStateView(SimAgvTelemetry telemetry, RmsTaskRegistry taskRegistry,
                          RmsProperties props, HostStats hostStats) {
        this.telemetry = telemetry;
        this.taskRegistry = taskRegistry;
        this.props = props;
        this.hostStats = hostStats;
    }

    public void setEffectiveRobotCode(String code) {
        this.effectiveRobotCode = code;
    }

    private String robotCode() {
        return (effectiveRobotCode != null && !effectiveRobotCode.isBlank())
                ? effectiveRobotCode : props.getRobot().getRobotCode();
    }

    /** 心跳 / 状态上报共用一份报文（心跳再加 heartbeat_at）。 */
    public RobotStatusReport snapshot() {
        RmsProperties.Robot identity = props.getRobot();
        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        SimAgvTelemetry.PoseSnapshot pose = telemetry.getMapPose();

        RmsTask active = taskRegistry.activeTask();
        String taskStatus = active == null ? "idle" : active.status().wireName();
        String mode = status == null || status.mode() == null
                ? "unknown"
                : status.mode().toLowerCase(Locale.ROOT);
        String mapName = status == null || status.mapName() == null ? "-" : status.mapName();
        double x = pose == null ? 0.0 : pose.x();
        double y = pose == null ? 0.0 : pose.y();
        double yaw = pose == null ? 0.0 : pose.yaw();
        double battery = status == null || Double.isNaN(status.battery()) ? 100.0 : status.battery();
        List<String> chargers = List.copyOf(props.getBehavior().getCompatibleChargers());

        return new RobotStatusReport(
                robotCode(),
                identity.getRobotSn(),
                identity.getRobotSn(),
                telemetry.isConnected() ? "online" : "offline",
                mapName + " / x=" + round(x) + ", y=" + round(y),
                battery,
                0.0,
                mapName,
                mapName,
                "auto",
                taskStatus,
                "normal",
                "offline",
                mode,
                mode,
                active != null && active.isChargingBehavior(),
                active != null && active.isHomingBehavior(),
                false,
                mapName,
                new RobotStatusReport.Position(round(x), round(y), round(yaw)),
                chargers,
                chargers,
                active == null ? null : active.taskId(),
                active == null ? null : taskStatus,
                OffsetDateTime.now()
        );
    }

    /** status/query 的 data（默认模块集：agv_status + position + battery + serial_num）。 */
    public BodyStatusData bodyStatusData() {
        RmsTask active = taskRegistry.activeTask();
        String taskStatus = active == null ? "idle" : active.status().wireName();
        int statusCode = active == null ? 0 : active.status().actionStatusCode();
        SimAgvTelemetry.PoseSnapshot pose = telemetry.getMapPose();
        double x = pose == null ? 0.0 : pose.x();
        double y = pose == null ? 0.0 : pose.y();
        double yaw = pose == null ? 0.0 : pose.yaw();
        double battery;
        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        battery = status == null || Double.isNaN(status.battery()) ? 100.0 : status.battery();
        String now = OffsetDateTime.now().toLocalDateTime().toString();

        return new BodyStatusData(
                new BodyStatusData.AgvStatus(taskStatus, statusCode, true, now),
                new BodyStatusData.PositionReport(
                        null, round(x), round(y), 0.0, round(yaw),
                        0.0, 0.0, Math.sin(yaw / 2.0), Math.cos(yaw / 2.0), now),
                new BodyStatusData.BatteryReport(
                        active != null && active.isChargingBehavior(), battery / 100.0, battery, now),
                props.getRobot().getRobotSn()
        );
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private final java.util.concurrent.atomic.AtomicLong statusSequence =
            new java.util.concurrent.atomic.AtomicLong(0);

    /** 心跳 payload：简单身份信息（对齐 RMS 真机格式，不是完整状态报告） */
    public HeartbeatPayload buildHeartbeatPayload() {
        RmsProperties.Robot robot = props.getRobot();
        return new HeartbeatPayload(
                "v1",
                robotCode(),
                robot.getRobotSn(),
                robot.getRobotName(),
                robot.getRobotType(),
                robot.getIp());
    }

    /** 状态上报信封：{data: {真机模块集}, serial_num, timestamp}（对齐真机 status/report 报文） */
    public StatusReportEnvelope buildStatusEnvelope() {
        RmsTask active = taskRegistry.activeTask();
        String taskStatus = active == null ? "idle" : active.status().wireName();
        boolean charging = active != null && active.isChargingBehavior();

        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        SimAgvTelemetry.PoseSnapshot pose = telemetry.getMapPose();
        SimAgvTelemetry.PoseSnapshot odom = telemetry.getOdomPose();
        double x = pose == null ? 0.0 : pose.x();
        double y = pose == null ? 0.0 : pose.y();
        double yaw = pose == null ? 0.0 : pose.yaw();
        String mode = status == null || status.mode() == null
                ? "auto" : status.mode().toLowerCase(Locale.ROOT);
        String mapName = status == null || status.mapName() == null ? "-" : status.mapName();
        double pct = status == null || Double.isNaN(status.battery()) ? 100.0 : status.battery();
        String now = OffsetDateTime.now().toLocalDateTime().toString();

        // 双电池包（id=1/2，同 ID 同百分比，电流充电为负、放电为正）
        List<BodyFullStatusData.Battery> battery = List.of(
                batteryPack(2, pct, charging, now),
                batteryPack(1, pct, charging, now));

        BodyFullStatusData data = new BodyFullStatusData(
                new BodyFullStatusData.AgvStatus(
                        taskStatus, active == null ? 0 : active.status().actionStatusCode(),
                        true, 0, now),
                battery,
                List.of(),
                INSPECTION_REPORT,
                new BodyFullStatusData.MapInfo(mapName, now),
                new BodyFullStatusData.Memory(
                        hostStats.availableMemoryKb(), hostStats.totalMemoryKb(),
                        hostStats.usedMemoryKb(), now),
                List.of(new BodyFullStatusData.Mode(mode, now)),
                List.of(new BodyFullStatusData.Odometry(
                        "odom",
                        odom == null ? 0.0 : round(odom.yaw()),
                        now,
                        round(telemetry.getOdomLinearX()), 0.0, round(telemetry.getOdomAngularZ()),
                        odom == null ? 0.0 : round(odom.x()),
                        odom == null ? 0.0 : round(odom.y()))),
                new BodyFullStatusData.Position(
                        mapName,
                        round(Math.cos(yaw / 2.0)), 0.0, 0.0, round(Math.sin(yaw / 2.0)),
                        now,
                        round(x), round(y), 0.0, round(yaw)),
                new BodyFullStatusData.RobotInfo(props.getRobot().getRobotName(), now),
                new BodyFullStatusData.SystemInfo(
                        hostStats.arch(), hostStats.cpuCount(),
                        hostStats.cpuUsagePercent() + "%",
                        hostStats.hostname(), hostStats.os(),
                        hostStats.uptimeSeconds(), now),
                List.of(
                        new BodyFullStatusData.Temperature(SIM_BATTERY_ID, "battery", 31.0, now),
                        new BodyFullStatusData.Temperature(SIM_BATTERY_ID, "battery", 31.0, now)));

        return new StatusReportEnvelope(
                data,
                statusSequence.incrementAndGet(),
                String.valueOf(System.currentTimeMillis()));
    }

    private static BodyFullStatusData.Battery batteryPack(int id, double pct, boolean charging, String now) {
        return new BodyFullStatusData.Battery(
                SIM_BATTERY_ID,
                round(FULL_CAPACITY_MAH * pct / 100.0),
                charging ? -8200.0 : 2700.0,
                0.0,
                id,
                charging,
                round(pct),
                round(pct),
                "agv",
                charging ? 1 : 0,
                now,
                round(voltageMv(pct)));
    }
}
