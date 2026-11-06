package com.agv.navdeployer.rms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "rms")
public class RmsProperties {

    private boolean enabled = false;

    private Zenoh zenoh = new Zenoh();
    private Robot robot = new Robot();
    private Report report = new Report();
    private Behavior behavior = new Behavior();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Zenoh getZenoh() {
        return zenoh;
    }

    public void setZenoh(Zenoh zenoh) {
        this.zenoh = zenoh;
    }

    public Robot getRobot() {
        return robot;
    }

    public void setRobot(Robot robot) {
        this.robot = robot;
    }

    public Report getReport() {
        return report;
    }

    public void setReport(Report report) {
        this.report = report;
    }

    public Behavior getBehavior() {
        return behavior;
    }

    public void setBehavior(Behavior behavior) {
        this.behavior = behavior;
    }

    public static class Zenoh {
        private long queryTimeoutMs = 5000;
        private boolean continueWithoutRegister = false;
        private String registerKey;
        private String robotKeyPrefix;
        private String configFile;

        public long getQueryTimeoutMs() {
            return queryTimeoutMs;
        }

        public void setQueryTimeoutMs(long queryTimeoutMs) {
            this.queryTimeoutMs = queryTimeoutMs;
        }

        public boolean isContinueWithoutRegister() {
            return continueWithoutRegister;
        }

        public void setContinueWithoutRegister(boolean continueWithoutRegister) {
            this.continueWithoutRegister = continueWithoutRegister;
        }

        public String getRegisterKey() {
            return registerKey;
        }

        public void setRegisterKey(String registerKey) {
            this.registerKey = registerKey;
        }

        public String getRobotKeyPrefix() {
            return robotKeyPrefix;
        }

        public void setRobotKeyPrefix(String robotKeyPrefix) {
            this.robotKeyPrefix = robotKeyPrefix;
        }

        public String getConfigFile() {
            return configFile;
        }

        public void setConfigFile(String configFile) {
            this.configFile = configFile;
        }
    }

    public static class Robot {
        private static final String DEFAULT_SN_SEED = "nav-deployer-sim-chassis-001";

        private String robotCode = "";
        private String robotSn;
        private String robotName = "robot";
        private String robotType = "qc-robot";
        private String model;
        private String hardwareVersion;
        private String firmwareVersion;
        private String manufacturer;
        private String ip;
        private String clientVersion;
        private String protocolVersion;
        private List<String> capabilities = new ArrayList<>();

        public String getRobotCode() {
            return robotCode;
        }

        public void setRobotCode(String robotCode) {
            this.robotCode = robotCode;
        }

        public static String defaultSn() {
            return java.util.UUID.nameUUIDFromBytes(DEFAULT_SN_SEED.getBytes())
                    .toString().replace("-", "");
        }

        public boolean isRobotSnConfigured() {
            return robotSn != null && !robotSn.isBlank();
        }

        public String getRobotSn() {
            return isRobotSnConfigured() ? robotSn : defaultSn();
        }

        public void setRobotSn(String robotSn) {
            // 空配置（如 ${RMS_ROBOT_SN:} 展开为空串）不覆盖：保持未配置状态，getter 回退默认 SN
            if (robotSn == null || robotSn.isBlank()) {
                return;
            }
            this.robotSn = robotSn.trim();
        }

        public String getRobotName() {
            return robotName;
        }

        public void setRobotName(String robotName) {
            this.robotName = robotName;
        }

        public String getRobotType() {
            return robotType;
        }

        public void setRobotType(String robotType) {
            this.robotType = robotType;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getManufacturer() {
            return manufacturer;
        }

        public void setManufacturer(String manufacturer) {
            this.manufacturer = manufacturer;
        }

        public String getIp() {
            if (ip == null || ip.isBlank()) {
                try {
                    return java.net.InetAddress.getLocalHost().getHostAddress();
                } catch (Exception ignored) {
                    return null;
                }
            }
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public String getHardwareVersion() {
            return hardwareVersion;
        }

        public void setHardwareVersion(String hardwareVersion) {
            this.hardwareVersion = hardwareVersion;
        }

        public String getFirmwareVersion() {
            return firmwareVersion;
        }

        public void setFirmwareVersion(String firmwareVersion) {
            this.firmwareVersion = firmwareVersion;
        }

        public String getClientVersion() {
            return clientVersion;
        }

        public void setClientVersion(String clientVersion) {
            this.clientVersion = clientVersion;
        }

        public String getProtocolVersion() {
            return protocolVersion;
        }

        public void setProtocolVersion(String protocolVersion) {
            this.protocolVersion = protocolVersion;
        }

        public List<String> getCapabilities() {
            return capabilities;
        }

        public void setCapabilities(List<String> capabilities) {
            this.capabilities = capabilities;
        }
    }

    public static class Report {
        private long heartbeatIntervalMs = 5000;
        private long statusIntervalMs = 5000;
        private boolean publishLegacyStatus = true;
        private boolean publishBodyStatus = true;
        private boolean verboseLog = false;
        private long periodicLogIntervalMs = 30_000;
        private long unchangedLogIntervalMs = 60_000;

        public long getPeriodicLogIntervalMs() {
            return periodicLogIntervalMs;
        }

        public void setPeriodicLogIntervalMs(long periodicLogIntervalMs) {
            this.periodicLogIntervalMs = periodicLogIntervalMs;
        }

        public long getUnchangedLogIntervalMs() {
            return unchangedLogIntervalMs;
        }

        public void setUnchangedLogIntervalMs(long unchangedLogIntervalMs) {
            this.unchangedLogIntervalMs = unchangedLogIntervalMs;
        }

        public boolean isVerboseLog() {
            return verboseLog;
        }

        public void setVerboseLog(boolean verboseLog) {
            this.verboseLog = verboseLog;
        }

        public long getHeartbeatIntervalMs() {
            return heartbeatIntervalMs;
        }

        public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
            this.heartbeatIntervalMs = heartbeatIntervalMs;
        }

        public long getStatusIntervalMs() {
            return statusIntervalMs;
        }

        public void setStatusIntervalMs(long statusIntervalMs) {
            this.statusIntervalMs = statusIntervalMs;
        }

        public boolean isPublishLegacyStatus() {
            return publishLegacyStatus;
        }

        public void setPublishLegacyStatus(boolean publishLegacyStatus) {
            this.publishLegacyStatus = publishLegacyStatus;
        }

        public boolean isPublishBodyStatus() {
            return publishBodyStatus;
        }

        public void setPublishBodyStatus(boolean publishBodyStatus) {
            this.publishBodyStatus = publishBodyStatus;
        }
    }

    public static class Behavior {
        private long inspectionDurationMs = 8000;
        private long armPhaseWallClockTimeoutMs = 60000;
        private long actionDurationMs = 3000;
        private String inspectionResult = "OK";
        private String inspectionImageDir = "";
        private String teachSampleDir = "";
        private String templateDir = "data/rms-templates";
        private Pose homePose;
        private List<String> compatibleChargers = new ArrayList<>();

        public long getInspectionDurationMs() {
            return inspectionDurationMs;
        }

        public void setInspectionDurationMs(long inspectionDurationMs) {
            this.inspectionDurationMs = inspectionDurationMs;
        }

        public long getArmPhaseWallClockTimeoutMs() {
            return armPhaseWallClockTimeoutMs;
        }

        public void setArmPhaseWallClockTimeoutMs(long armPhaseWallClockTimeoutMs) {
            this.armPhaseWallClockTimeoutMs = armPhaseWallClockTimeoutMs;
        }

        public long getActionDurationMs() {
            return actionDurationMs;
        }

        public void setActionDurationMs(long actionDurationMs) {
            this.actionDurationMs = actionDurationMs;
        }

        public String getInspectionResult() {
            return inspectionResult;
        }

        public void setInspectionResult(String inspectionResult) {
            this.inspectionResult = inspectionResult;
        }

        public String getInspectionImageDir() {
            return inspectionImageDir;
        }

        public void setInspectionImageDir(String inspectionImageDir) {
            this.inspectionImageDir = inspectionImageDir;
        }

        public String getTeachSampleDir() {
            return teachSampleDir;
        }

        public void setTeachSampleDir(String teachSampleDir) {
            this.teachSampleDir = teachSampleDir;
        }

        public String getTemplateDir() {
            return templateDir;
        }

        public void setTemplateDir(String templateDir) {
            this.templateDir = templateDir;
        }

        public Pose getHomePose() {
            return homePose;
        }

        public void setHomePose(Pose homePose) {
            this.homePose = homePose;
        }

        public List<String> getCompatibleChargers() {
            return compatibleChargers;
        }

        public void setCompatibleChargers(List<String> compatibleChargers) {
            this.compatibleChargers = compatibleChargers;
        }
    }

    public static class Pose {
        private double x;
        private double y;
        private double yaw;

        public Pose() {
        }

        public Pose(double x, double y, double yaw) {
            this.x = x;
            this.y = y;
            this.yaw = yaw;
        }

        public double getX() {
            return x;
        }

        public void setX(double x) {
            this.x = x;
        }

        public double getY() {
            return y;
        }

        public void setY(double y) {
            this.y = y;
        }

        public double getYaw() {
            return yaw;
        }

        public void setYaw(double yaw) {
            this.yaw = yaw;
        }
    }
}
