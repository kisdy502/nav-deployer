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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class RobotStateView {

    private static final String SIM_BATTERY_ID = "313536302D363033313";
    private static final double FULL_CAPACITY_MAH = 40000.0;

    private static double voltageMv(double pct) {
        return 45000.0 + 120.0 * pct;
    }

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
    private final com.agv.navdeployer.sim.ArmStateMonitor armStateMonitor;
    private final com.agv.navdeployer.service.RobotOperationalService operationalService;

    private volatile String effectiveRobotCode;
    private final Path codeStoreFile;

    public RobotStateView(SimAgvTelemetry telemetry, RmsTaskRegistry taskRegistry,
                          RmsProperties props, HostStats hostStats,
                          com.agv.navdeployer.sim.ArmStateMonitor armStateMonitor,
                          com.agv.navdeployer.service.RobotOperationalService operationalService) {
        this.telemetry = telemetry;
        this.taskRegistry = taskRegistry;
        this.props = props;
        this.hostStats = hostStats;
        this.armStateMonitor = armStateMonitor;
        this.operationalService = operationalService;
        this.codeStoreFile = Path.of(System.getProperty("user.home"),
                ".nav-deployer", "rms-robot-code");
        this.effectiveRobotCode = loadPersistedCode();
    }

    public void setEffectiveRobotCode(String code) {
        this.effectiveRobotCode = code;
        persistCode(code);
    }

    public String robotCode() {
        return (effectiveRobotCode != null && !effectiveRobotCode.isBlank())
                ? effectiveRobotCode : props.getRobot().getRobotCode();
    }

    private String loadPersistedCode() {
        try {
            if (!Files.exists(codeStoreFile)) {
                return null;
            }
            String code = Files.readString(codeStoreFile, StandardCharsets.UTF_8).trim();
            return code.isEmpty() ? null : code;
        } catch (IOException exception) {
            return null;
        }
    }

    private void persistCode(String code) {
        try {
            if (code == null || code.isBlank()) {
                Files.deleteIfExists(codeStoreFile);
            } else {
                Files.createDirectories(codeStoreFile.getParent());
                Files.writeString(codeStoreFile, code, StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
        }
    }

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
        double battery = batteryPct(status);
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
                operationalService.isDecommissioned()
                        ? operationalService.getReason() : "normal",
                "offline",
                mode,
                mode,
                isCharging(active, status),
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
        battery = batteryPct(status);
        String now = OffsetDateTime.now().toLocalDateTime().toString();

        return new BodyStatusData(
                new BodyStatusData.AgvStatus(taskStatus, statusCode, true, now),
                new BodyStatusData.PositionReport(
                        null, round(x), round(y), 0.0, round(yaw),
                        0.0, 0.0, Math.sin(yaw / 2.0), Math.cos(yaw / 2.0), now),
                new BodyStatusData.BatteryReport(
                        isCharging(active, status), battery / 100.0, battery, now),
                props.getRobot().getRobotSn()
        );
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static double batteryPct(SimAgvTelemetry.StatusSnapshot status) {
        if (status == null || Double.isNaN(status.battery())
                || Boolean.FALSE.equals(status.batteryDataValid())) {
            return 100.0;
        }
        return status.battery();
    }

    private static boolean isCharging(RmsTask active, SimAgvTelemetry.StatusSnapshot status) {
        if (status != null && Boolean.TRUE.equals(status.charging())) {
            return true;
        }
        return active != null && active.isChargingBehavior();
    }

    private final java.util.concurrent.atomic.AtomicLong statusSequence =
            new java.util.concurrent.atomic.AtomicLong(0);

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

    public StatusReportEnvelope buildStatusEnvelope() {
        RmsTask active = taskRegistry.activeTask();
        String taskStatus = active == null ? "idle" : active.status().wireName();
        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        boolean charging = isCharging(active, status);

        SimAgvTelemetry.PoseSnapshot pose = telemetry.getMapPose();
        SimAgvTelemetry.PoseSnapshot odom = telemetry.getOdomPose();
        double x = pose == null ? 0.0 : pose.x();
        double y = pose == null ? 0.0 : pose.y();
        double yaw = pose == null ? 0.0 : pose.yaw();
        String mode = status == null || status.mode() == null
                ? "auto" : status.mode().toLowerCase(Locale.ROOT);
        String mapName = status == null || status.mapName() == null ? "-" : status.mapName();
        double pct = batteryPct(status);
        String now = OffsetDateTime.now().toLocalDateTime().toString();

        List<BodyFullStatusData.Battery> battery = List.of(
                batteryPack(2, pct, charging, now),
                batteryPack(1, pct, charging, now));

        boolean decommissioned = operationalService.isDecommissioned();
        String agvState = decommissioned ? operationalService.getReason() : taskStatus;
        int statusCode = decommissioned ? -1 : (active == null ? 0 : active.status().actionStatusCode());
        List<Map<String, Object>> exceptions = decommissioned
                ? List.of(Map.of(
                        "level", 2,
                        "status", operationalService.getReason(),
                        "description", "robot decommissioned: " + operationalService.getReason(),
                        "update_time", now))
                : List.of();

        BodyFullStatusData data = new BodyFullStatusData(
                new BodyFullStatusData.AgvStatus(
                        agvState, statusCode,
                        !decommissioned, 0, now),
                battery,
                exceptions,
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
                        round(telemetry.getOdomLinearX()), round(telemetry.getOdomLinearY()), round(telemetry.getOdomAngularZ()),
                        odom == null ? 0.0 : round(odom.x()),
                        odom == null ? 0.0 : round(odom.y()))),
                new BodyFullStatusData.Position(
                        "L1",
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
                        new BodyFullStatusData.Temperature(SIM_BATTERY_ID, "battery", 31.0, now)),
                armStateMonitor == null ? null : java.util.Map.of(
                        "state", armStateMonitor.state().name(),
                        "movement_allowed", armStateMonitor.movementAllowed(),
                        "update_time", now));
        RmsProperties.Robot robot = props.getRobot();
        return new StatusReportEnvelope(
                data,
                robot.getRobotSn(),
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
