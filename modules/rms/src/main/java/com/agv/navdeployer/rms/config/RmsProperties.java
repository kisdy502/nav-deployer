package com.agv.navdeployer.rms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * RMS 对接配置。rms.enabled=false 时整条链路空载（默认）。
 */
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

    /**
     * zenoh 会话参数。连接配置（endpoints/scouting/TLS/namespace 等）走 zenoh 标准做法：
     * classpath 的 config-json5.json，或 config-file 指向外部文件——yaml 不再承载连接项。
     */
    public static class Zenoh {
        /** query-reply（注册/心跳查询）超时 */
        private long queryTimeoutMs = 5000;
        /** 注册失败后是否继续运行（不上报 RMS） */
        private boolean continueWithoutRegister = false;
        /** 注册 key */
        private String registerKey;
        /** legacy key 前缀（.../robot/{code}/heartbeat 等） */
        private String robotKeyPrefix;
        /** 外部 zenoh 配置文件路径（空 = classpath config-json5.json） */
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

    /**
     * 机器人身份。
     * robot_code：不配置（留空）——由 RMS 注册时分配，注册后自动切换到分配值。
     * robot_sn：唯一硬件标识（RMS 去重键），跨重启不变 → RMS 按 SN 找到已有机器人走更新。
     * 配置优先：rms.robot.robot_sn（yml 直接写值，或 ${RMS_ROBOT_SN:} 环境变量占位）非空即强制覆盖；
     * 留空才回退 {@link #defaultSn()}——注意默认值所有实例共享，多机联调会汇到同一台机器人。
     */
    public static class Robot {
        /** 默认 SN 的固定种子：模拟一台"出厂"仿真底盘，所有实例生成相同 SN（见 {@link #defaultSn()}） */
        private static final String DEFAULT_SN_SEED = "nav-deployer-sim-chassis-001";

        /** 留空 = RMS 分配（推荐） */
        private String robotCode = "";
        /** 配置注入的 SN（已 trim）；null/blank = 未配置，getter 回退共享默认值 */
        private String robotSn;
        private String robotName = "robot";
        /** body key 用：zioneer/{robot_type}/robot/{robot_code}/api/v1 */
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

        /** 固定种子生成的默认 SN（32 位 hex，格式与真机一致；所有实例相同）。 */
        public static String defaultSn() {
            return java.util.UUID.nameUUIDFromBytes(DEFAULT_SN_SEED.getBytes())
                    .toString().replace("-", "");
        }

        /** SN 是否由配置显式指定（false = 使用共享默认值，多机联调会共用同一台机器人）。 */
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
                // 未配置时自动取本机 IP（注册信息展示用）
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

    /** 心跳/状态上报周期。 */
    public static class Report {
        private long heartbeatIntervalMs = 5000;
        private long statusIntervalMs = 5000;
        /** 是否同时发布 legacy key（...robot/{code}/status/report） */
        private boolean publishLegacyStatus = true;
        /** 是否发布 body key（.../api/v1/status/report） */
        private boolean publishBodyStatus = true;
        /** 排查开关：逐条打印心跳/状态上报结果（默认只打 30s 汇总） */
        private boolean verboseLog = false;

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

    /** 任务行为参数。 */
    public static class Behavior {
        /** 质检占位执行时长（仿真机械臂就位前的模拟时长） */
        private long inspectionDurationMs = 8000;
        /** 机械臂阶段到位等待的墙钟总时长下限：arm_move 时长是仿真时间，宿主机过载 RTF<1 时
         *  需按墙钟给足（RTF 0.1 时 6 仿真秒≈60 墙钟秒），可按机器负载调整 */
        private long armPhaseWallClockTimeoutMs = 60000;
        /** 行为树 action 占位执行时长（fetch_cable 等无专用执行器的模板） */
        private long actionDurationMs = 3000;
        /** 质检模拟结果：OK / NG */
        private String inspectionResult = "OK";
        /** 仿真质检图片目录（含子目录）：打包 zip 时随机取 2~3 张真实图片；为空或无图时回退占位图 */
        private String inspectionImageDir = "";
        /** teach 采集样例目录（含子目录）：teach/capture 打包 zip 时取前几个文件；为空回退 application.yaml+runtime 信息 */
        private String teachSampleDir = "";
        /** 充电点坐标（charge / replace_battery 模板目标；未配置则此类任务失败） */
        private Pose chargePose;
        /** 回家点坐标（return_home 模板目标） */
        private Pose homePose;
        /** compatible_chargers 上报值 */
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

        public Pose getChargePose() {
            return chargePose;
        }

        public void setChargePose(Pose chargePose) {
            this.chargePose = chargePose;
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
