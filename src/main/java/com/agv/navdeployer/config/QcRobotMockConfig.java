package com.agv.navdeployer.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class QcRobotMockConfig {

    private RobotConfig robot = new RobotConfig();
    private ZenohConfig zenoh = new ZenohConfig();
    private RuntimeConfig runtime = new RuntimeConfig();
    private SimulationConfig simulation = new SimulationConfig();
    private MotionConfig motion = new MotionConfig();
    private MapsConfig maps = new MapsConfig();
    private FileIoConfig fileIo = new FileIoConfig();
    private RobotManagementConfig robotManagement = new RobotManagementConfig();
    private SimAgvConfig simAgv = new SimAgvConfig();

    public static QcRobotMockConfig defaults() {
        return new QcRobotMockConfig();
    }

    public RobotConfig getRobot() {
        return robot;
    }

    public void setRobot(RobotConfig robot) {
        this.robot = robot == null ? new RobotConfig() : robot;
    }

    public ZenohConfig getZenoh() {
        return zenoh;
    }

    public void setZenoh(ZenohConfig zenoh) {
        this.zenoh = zenoh == null ? new ZenohConfig() : zenoh;
    }

    public RuntimeConfig getRuntime() {
        return runtime;
    }

    public void setRuntime(RuntimeConfig runtime) {
        this.runtime = runtime == null ? new RuntimeConfig() : runtime;
    }

    public SimulationConfig getSimulation() {
        return simulation;
    }

    public void setSimulation(SimulationConfig simulation) {
        this.simulation = simulation == null ? new SimulationConfig() : simulation;
    }

    public MotionConfig getMotion() {
        return motion;
    }

    public void setMotion(MotionConfig motion) {
        this.motion = motion == null ? new MotionConfig() : motion;
    }

    public MapsConfig getMaps() {
        return maps;
    }

    public void setMaps(MapsConfig maps) {
        this.maps = maps == null ? new MapsConfig() : maps;
    }

    public FileIoConfig getFileIo() {
        return fileIo;
    }

    public void setFileIo(FileIoConfig fileIo) {
        this.fileIo = fileIo == null ? new FileIoConfig() : fileIo;
    }

    public RobotManagementConfig getRobotManagement() {
        return robotManagement;
    }

    public void setRobotManagement(RobotManagementConfig robotManagement) {
        this.robotManagement = robotManagement == null ? new RobotManagementConfig() : robotManagement;
    }

    public SimAgvConfig getSimAgv() {
        return simAgv;
    }

    public void setSimAgv(SimAgvConfig simAgv) {
        this.simAgv = simAgv == null ? new SimAgvConfig() : simAgv;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RobotConfig {
        @ConfigOption(
                path = "robot.robot-code",
                description = "Initial robot code. Registration may replace it with the code returned by robot-management-service.",
                defaultValue = "RB_20260427_00105",
                env = "QC_ROBOT_CODE"
        )
        private String robotCode = "RB_20260427_00105";

        @ConfigOption(
                path = "robot.serial-no",
                description = "Unique robot serial number used for registration and body status queries.",
                defaultValue = "SN_RB_20260427_00105",
                env = "QC_ROBOT_SERIAL_NO"
        )
        private String serialNo = "SN_RB_20260427_00105";

        @ConfigOption(
                path = "robot.robot-name",
                description = "Display name registered in robot-management-service.",
                defaultValue = "RB_20260427_00105",
                env = "QC_ROBOT_NAME"
        )
        private String robotName = "RB_20260427_00105";

        @ConfigOption(
                path = "robot.robot-type",
                description = "Robot type segment used in zioneer/{robot_type}/robot/{robot_code}/api/v1 keys.",
                defaultValue = "qc-robot",
                env = "QC_ROBOT_TYPE"
        )
        private String robotType = "qc-robot";

        @ConfigOption(
                path = "robot.robot-type-no",
                description = "Optional robot type business code sent during registration. Keep blank unless it matches robot-management-service.",
                defaultValue = "",
                env = "QC_ROBOT_TYPE_NO"
        )
        private String robotTypeNo = "";

        @ConfigOption(
                path = "robot.ip",
                description = "Robot IP address sent during registration.",
                defaultValue = "127.0.0.1",
                env = "QC_ROBOT_IP"
        )
        private String ip = "127.0.0.1";

        @ConfigOption(
                path = "robot.model",
                description = "Robot model sent during registration.",
                defaultValue = "QC-MOCK-MODEL",
                env = "QC_ROBOT_MODEL"
        )
        private String model = "QC-MOCK-MODEL";

        @ConfigOption(
                path = "robot.hardware-version",
                description = "Hardware version sent during registration.",
                defaultValue = "mock-hw-1.0",
                env = "QC_ROBOT_HARDWARE_VERSION"
        )
        private String hardwareVersion = "mock-hw-1.0";

        @ConfigOption(
                path = "robot.firmware-version",
                description = "Firmware version sent during registration.",
                defaultValue = "mock-fw-1.0",
                env = "QC_ROBOT_FIRMWARE_VERSION"
        )
        private String firmwareVersion = "mock-fw-1.0";

        @ConfigOption(
                path = "robot.client-version",
                description = "Mock client version sent during registration.",
                defaultValue = "0.0.1-SNAPSHOT",
                env = "QC_ROBOT_CLIENT_VERSION"
        )
        private String clientVersion = "0.0.1-SNAPSHOT";

        @ConfigOption(
                path = "robot.manufacturer",
                description = "Manufacturer sent during registration.",
                defaultValue = "zioneer-mock",
                env = "QC_ROBOT_MANUFACTURER"
        )
        private String manufacturer = "zioneer-mock";

        @ConfigOption(
                path = "robot.mac-address",
                description = "MAC address sent during registration.",
                defaultValue = "00:11:22:33:44:55",
                env = "QC_ROBOT_MAC_ADDRESS"
        )
        private String macAddress = "00:11:22:33:44:55";

        @ConfigOption(
                path = "robot.vendor-type-code",
                description = "Vendor type code sent during registration.",
                defaultValue = "QC-MOCK",
                env = "QC_ROBOT_VENDOR_TYPE_CODE"
        )
        private String vendorTypeCode = "QC-MOCK";

        @ConfigOption(
                path = "robot.video-url",
                description = "Optional video stream URL sent during registration.",
                defaultValue = "ws://127.0.0.1:8080/qc-robot/mock",
                env = "QC_ROBOT_VIDEO_URL"
        )
        private String videoUrl = "ws://127.0.0.1:8080/qc-robot/mock";

        @ConfigOption(
                path = "robot.protocol-version",
                description = "Robot body protocol version sent during registration.",
                defaultValue = "v1",
                env = "QC_ROBOT_PROTOCOL_VERSION"
        )
        private String protocolVersion = "v1";

        @ConfigOption(
                path = "robot.capabilities",
                description = "Comma separated capability names sent during registration.",
                defaultValue = "navigate,start-activity,stop-task,pause-task,resume-task,mapping,quality-inspection,charge,swap-battery,return-home",
                env = "QC_ROBOT_CAPABILITIES"
        )
        private List<String> capabilities = new ArrayList<>(List.of(
                "navigate",
                "start-activity",
                "stop-task",
                "pause-task",
                "resume-task",
                "mapping",
                "quality-inspection",
                "charge",
                "swap-battery",
                "return-home"
        ));

        public String getRobotCode() {
            return robotCode;
        }

        public void setRobotCode(String robotCode) {
            this.robotCode = robotCode;
        }

        public String getSerialNo() {
            return serialNo;
        }

        public void setSerialNo(String serialNo) {
            this.serialNo = serialNo;
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

        public String getRobotTypeNo() {
            return robotTypeNo;
        }

        public void setRobotTypeNo(String robotTypeNo) {
            this.robotTypeNo = robotTypeNo;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
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

        public String getManufacturer() {
            return manufacturer;
        }

        public void setManufacturer(String manufacturer) {
            this.manufacturer = manufacturer;
        }

        public String getMacAddress() {
            return macAddress;
        }

        public void setMacAddress(String macAddress) {
            this.macAddress = macAddress;
        }

        public String getVendorTypeCode() {
            return vendorTypeCode;
        }

        public void setVendorTypeCode(String vendorTypeCode) {
            this.vendorTypeCode = vendorTypeCode;
        }

        public String getVideoUrl() {
            return videoUrl;
        }

        public void setVideoUrl(String videoUrl) {
            this.videoUrl = videoUrl;
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
            this.capabilities = capabilities == null ? new ArrayList<>() : new ArrayList<>(capabilities);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ZenohConfig {
        @ConfigOption(
                path = "zenoh.transport",
                description = "tcp or tls. When omitted, use legacy tls.enabled; otherwise default to tcp.",
                defaultValue = "tcp",
                env = "ZENOH_TRANSPORT"
        )
        private String transport;
        @ConfigOption(
                path = "zenoh.endpoints",
                description = "Zenoh router endpoints. Plain host:port values use the selected transport.",
                defaultValue = "127.0.0.1:7447",
                env = "ZENOH_FALLBACK_ENDPOINTS"
        )
        private List<String> endpoints = new ArrayList<>(List.of("127.0.0.1:7447"));

        @ConfigOption(
                path = "zenoh.config-file",
                description = "Path to a standalone Zenoh JSON5 session config (e.g. config/config5.json) holding connect endpoints, scouting, namespace and TLS transport. When the file exists it replaces the yml zenoh session fields; blank disables the split.",
                defaultValue = "",
                env = "QC_ROBOT_ZENOH_CONFIG_FILE"
        )
        private String configFile = "";

        @ConfigOption(
                path = "zenoh.namespace",
                description = "Optional Zenoh session namespace shared with robot-management-service. When set, it is included in the Zenoh JSON5 config (e.g. cz_qc) so the mock uses the same namespace as the server.",
                defaultValue = "",
                env = "QC_ROBOT_ZENOH_NAMESPACE"
        )
        private String namespace = "";

        @ConfigOption(
                path = "zenoh.register-key",
                description = "Robot-management Zenoh registration query key.",
                defaultValue = "zioneer/robot-management-service/api/v1/robot-mgr/register",
                env = "QC_ROBOT_REGISTER_KEY"
        )
        private String registerKey = "zioneer/robot-management-service/api/v1/robot-mgr/register";

        @ConfigOption(
                path = "zenoh.robot-key-prefix",
                description = "Legacy robot scoped key prefix used for compatible heartbeat/status publishing.",
                defaultValue = "robot-management/robots",
                env = "QC_ROBOT_KEY_PREFIX"
        )
        private String robotKeyPrefix = "robot-management/robots";

        @ConfigOption(
                path = "zenoh.query-timeout-ms",
                description = "Timeout for registration, heartbeat and robot body query replies.",
                defaultValue = "5000",
                env = "QC_ROBOT_QUERY_TIMEOUT_MS"
        )
        private long queryTimeoutMs = 5000L;

        @ConfigOption(
                path = "zenoh.register",
                description = "Whether to register with robot-management-service before exposing runtime APIs.",
                defaultValue = "true",
                env = "QC_ROBOT_REGISTER"
        )
        private boolean register = true;

        @ConfigOption(
                path = "zenoh.continue-without-register",
                description = "Whether the mock keeps running if registration query fails.",
                defaultValue = "true",
                env = "QC_ROBOT_CONTINUE_WITHOUT_REGISTER"
        )
        private boolean continueWithoutRegister = true;

        private ZenohTlsConfig tls = new ZenohTlsConfig();

        public String getTransport() {
            return transport == null || transport.isBlank()
                    ? (tls.isEnabled() ? "tls" : "tcp") : transport.trim().toLowerCase(java.util.Locale.ROOT);
        }

        public void setTransport(String transport) {
            this.transport = transport;
        }

        // Explicit transport=tls keeps the historical mTLS default; legacy enabled keeps its default.
        public boolean resolveMutualTls() {
            return tls.enableMtls != null ? tls.enableMtls
                    : transport != null && "tls".equalsIgnoreCase(transport.trim());
        }

        public List<String> getEndpoints() {
            return endpoints;
        }

        public void setEndpoints(List<String> endpoints) {
            this.endpoints = endpoints == null ? new ArrayList<>() : new ArrayList<>(endpoints);
        }

        public String getConfigFile() {
            return configFile;
        }

        public void setConfigFile(String configFile) {
            this.configFile = configFile;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
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

        public long getQueryTimeoutMs() {
            return queryTimeoutMs;
        }

        public void setQueryTimeoutMs(long queryTimeoutMs) {
            this.queryTimeoutMs = queryTimeoutMs;
        }

        public boolean isRegister() {
            return register;
        }

        public void setRegister(boolean register) {
            this.register = register;
        }

        public boolean isContinueWithoutRegister() {
            return continueWithoutRegister;
        }

        public void setContinueWithoutRegister(boolean continueWithoutRegister) {
            this.continueWithoutRegister = continueWithoutRegister;
        }

        public ZenohTlsConfig getTls() {
            return tls;
        }

        public void setTls(ZenohTlsConfig tls) {
            this.tls = tls == null ? new ZenohTlsConfig() : tls;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ZenohTlsConfig {
        @ConfigOption(
                path = "zenoh.tls.enabled",
                description = "Legacy TLS switch used only when zenoh.transport is omitted.",
                defaultValue = "false",
                env = "ZENOH_TLS_ENABLED"
        )
        private boolean enabled = false;

        @ConfigOption(
                path = "zenoh.tls.root-ca-certificate",
                description = "Path to the PEM CA certificate used to verify the Zenoh router certificate. Leave blank to use the system trust store.",
                defaultValue = "",
                env = "ZENOH_TLS_ROOT_CA_CERTIFICATE"
        )
        private String rootCaCertificate = "";

        @ConfigOption(
                path = "zenoh.tls.enable-mtls",
                description = "Enable client authentication. Defaults to true for explicit transport=tls, false for legacy tls.enabled.",
                defaultValue = "auto",
                env = "ZENOH_TLS_ENABLE_MTLS"
        )
        private Boolean enableMtls;

        @ConfigOption(
                path = "zenoh.tls.connect-certificate",
                description = "Path to the PEM client certificate presented to the router when enable_mtls is true.",
                defaultValue = "",
                env = "ZENOH_TLS_CONNECT_CERTIFICATE"
        )
        private String connectCertificate = "";

        @ConfigOption(
                path = "zenoh.tls.connect-private-key",
                description = "Path to the PEM client private key presented to the router when enable_mtls is true.",
                defaultValue = "",
                env = "ZENOH_TLS_CONNECT_PRIVATE_KEY"
        )
        private String connectPrivateKey = "";

        @ConfigOption(
                path = "zenoh.tls.verify-name-on-connect",
                description = "Whether to verify the Zenoh server certificate name (CN/SAN) against the endpoint. Set false when the router certificate CN is not the endpoint host/IP.",
                defaultValue = "true",
                env = "ZENOH_TLS_VERIFY_NAME_ON_CONNECT"
        )
        private boolean verifyNameOnConnect = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRootCaCertificate() {
            return rootCaCertificate;
        }

        public void setRootCaCertificate(String rootCaCertificate) {
            this.rootCaCertificate = rootCaCertificate;
        }

        public boolean isEnableMtls() {
            return Boolean.TRUE.equals(enableMtls);
        }

        public void setEnableMtls(boolean enableMtls) {
            this.enableMtls = enableMtls;
        }

        public String getConnectCertificate() {
            return connectCertificate;
        }

        public void setConnectCertificate(String connectCertificate) {
            this.connectCertificate = connectCertificate;
        }

        public String getConnectPrivateKey() {
            return connectPrivateKey;
        }

        public void setConnectPrivateKey(String connectPrivateKey) {
            this.connectPrivateKey = connectPrivateKey;
        }

        public boolean isVerifyNameOnConnect() {
            return verifyNameOnConnect;
        }

        public void setVerifyNameOnConnect(boolean verifyNameOnConnect) {
            this.verifyNameOnConnect = verifyNameOnConnect;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RobotManagementConfig {
        @ConfigOption(
                path = "robot-management.enabled",
                description = "Whether startup should call robot-management REST APIs to create the robot group and assign the registered robot to it.",
                defaultValue = "true",
                env = "ROBOT_MANAGEMENT_ENABLED"
        )
        private boolean enabled = true;

        @ConfigOption(
                path = "robot-management.base-url",
                description = "Base URL of robot-management-service REST API.",
                defaultValue = "http://127.0.0.1:18083",
                env = "ROBOT_MANAGEMENT_BASE_URL"
        )
        private String baseUrl = "http://127.0.0.1:18083";

        @ConfigOption(
                path = "robot-management.group-no",
                description = "Robot group number to ensure and assign to the registered mock robot.",
                defaultValue = "RG_20260407_00001",
                env = "ROBOT_MANAGEMENT_GROUP_NO"
        )
        private String groupNo = "RG_20260407_00001";

        @ConfigOption(
                path = "robot-management.group-name",
                description = "Robot group display name used when creating the group.",
                defaultValue = "RG_20260407_00001",
                env = "ROBOT_MANAGEMENT_GROUP_NAME"
        )
        private String groupName = "RG_20260407_00001";

        @ConfigOption(
                path = "robot-management.group-description",
                description = "Robot group description used when creating the group.",
                defaultValue = "QC robot mock RMF fleet group",
                env = "ROBOT_MANAGEMENT_GROUP_DESCRIPTION"
        )
        private String groupDescription = "QC robot mock RMF fleet group";

        @ConfigOption(
                path = "robot-management.request-timeout-ms",
                description = "Timeout for robot-management REST requests.",
                defaultValue = "5000",
                env = "ROBOT_MANAGEMENT_REQUEST_TIMEOUT_MS"
        )
        private long requestTimeoutMs = 5000L;

        @ConfigOption(
                path = "robot-management.auth-token",
                description = "Optional bearer token for robot-management REST APIs.",
                defaultValue = "",
                env = "ROBOT_MANAGEMENT_AUTH_TOKEN"
        )
        private String authToken = "";

        @ConfigOption(
                path = "robot-management.continue-on-error",
                description = "Whether the mock keeps running when group creation or assignment fails.",
                defaultValue = "true",
                env = "ROBOT_MANAGEMENT_CONTINUE_ON_ERROR"
        )
        private boolean continueOnError = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getGroupNo() {
            return groupNo;
        }

        public void setGroupNo(String groupNo) {
            this.groupNo = groupNo;
        }

        public String getGroupName() {
            return groupName;
        }

        public void setGroupName(String groupName) {
            this.groupName = groupName;
        }

        public String getGroupDescription() {
            return groupDescription;
        }

        public void setGroupDescription(String groupDescription) {
            this.groupDescription = groupDescription;
        }

        public long getRequestTimeoutMs() {
            return requestTimeoutMs;
        }

        public void setRequestTimeoutMs(long requestTimeoutMs) {
            this.requestTimeoutMs = requestTimeoutMs;
        }

        public String getAuthToken() {
            return authToken;
        }

        public void setAuthToken(String authToken) {
            this.authToken = authToken;
        }

        public boolean isContinueOnError() {
            return continueOnError;
        }

        public void setContinueOnError(boolean continueOnError) {
            this.continueOnError = continueOnError;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RuntimeConfig {
        @ConfigOption(
                path = "runtime.heartbeat-interval-ms",
                description = "Interval for heartbeat query/report publishing.",
                defaultValue = "1000",
                env = "QC_ROBOT_HEARTBEAT_INTERVAL_MS"
        )
        private long heartbeatIntervalMs = 1000L;

        @ConfigOption(
                path = "runtime.status-interval-ms",
                description = "Interval for body status/report publishing.",
                defaultValue = "1000",
                env = "QC_ROBOT_STATUS_INTERVAL_MS"
        )
        private long statusIntervalMs = 1000L;

        @ConfigOption(
                path = "runtime.run-seconds",
                description = "Maximum runtime. Zero or negative means run until process shutdown.",
                defaultValue = "0",
                env = "QC_ROBOT_RUN_SECONDS"
        )
        private long runSeconds = 0L;

        @ConfigOption(
                path = "runtime.scheduler-threads",
                description = "Scheduled executor pool size.",
                defaultValue = "4",
                env = "QC_ROBOT_SCHEDULER_THREADS"
        )
        private int schedulerThreads = 4;

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

        public long getRunSeconds() {
            return runSeconds;
        }

        public void setRunSeconds(long runSeconds) {
            this.runSeconds = runSeconds;
        }

        public int getSchedulerThreads() {
            return schedulerThreads;
        }

        public void setSchedulerThreads(int schedulerThreads) {
            this.schedulerThreads = schedulerThreads;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SimulationConfig {
        @ConfigOption(
                path = "simulation.auto-complete",
                description = "Whether running tasks automatically complete when simulated motion, activity or charging criteria are satisfied.",
                defaultValue = "true",
                env = "QC_ROBOT_AUTO_COMPLETE"
        )
        private boolean autoComplete = true;

        @ConfigOption(
                path = "simulation.task-complete-delay-ms",
                description = "Legacy generic completion delay kept for compatibility. Prefer activity-complete-delay-ms for business actions.",
                defaultValue = "1000",
                env = "QC_ROBOT_TASK_COMPLETE_DELAY_MS"
        )
        private long taskCompleteDelayMs = 1000L;

        @ConfigOption(
                path = "simulation.complete-on-start-task-types",
                description = "Comma separated task markers that should publish a completed task result immediately after start succeeds. Useful for RMF integration loops that wait for task/result_report before sending the next command.",
                defaultValue = "",
                env = "QC_ROBOT_COMPLETE_ON_START_TASK_TYPES"
        )
        private List<String> completeOnStartTaskTypes = new ArrayList<>();

        @ConfigOption(
                path = "simulation.fail-navigation",
                description = "Whether navigation tasks should publish a failed task/result_report instead of a completed one. Useful for RMF failure-path testing.",
                defaultValue = "false",
                env = "QC_ROBOT_FAIL_NAVIGATION"
        )
        private boolean failNavigation = false;

        @ConfigOption(
                path = "simulation.reject-navigation",
                description = "Whether navigation task start commands should be rejected immediately instead of accepted.",
                defaultValue = "false",
                env = "QC_ROBOT_REJECT_NAVIGATION"
        )
        private boolean rejectNavigation = false;

        @ConfigOption(
                path = "simulation.reject-activity",
                description = "Whether activity/action start commands should be rejected immediately instead of accepted.",
                defaultValue = "false",
                env = "QC_ROBOT_REJECT_ACTIVITY"
        )
        private boolean rejectActivity = false;

        @ConfigOption(
                path = "simulation.publish-body-status-report",
                description = "Whether to publish zioneer/{robot_type}/robot/{robot_code}/api/v1/status/report.",
                defaultValue = "true",
                env = "QC_ROBOT_PUBLISH_BODY_STATUS_REPORT"
        )
        private boolean publishBodyStatusReport = true;

        @ConfigOption(
                path = "simulation.publish-legacy-status-report",
                description = "Whether to also publish robot-management/robots/{robot_code}/status/report.",
                defaultValue = "true",
                env = "QC_ROBOT_PUBLISH_LEGACY_STATUS_REPORT"
        )
        private boolean publishLegacyStatusReport = true;

        @ConfigOption(
                path = "simulation.initial-battery-percent",
                description = "Initial battery percentage reported by the mock robot.",
                defaultValue = "92",
                env = "QC_ROBOT_INITIAL_BATTERY_PERCENT"
        )
        private int initialBatteryPercent = 92;

        @ConfigOption(
                path = "simulation.activity-complete-delay-ms",
                description = "Business action duration for quality inspection and other non-navigation activities.",
                defaultValue = "500",
                env = "QC_ROBOT_ACTIVITY_COMPLETE_DELAY_MS"
        )
        private long activityCompleteDelayMs = 500L;

        @ConfigOption(
                path = "simulation.charge-complete-battery-percent",
                description = "Battery percentage at which a charge or replace_battery task is considered completed.",
                defaultValue = "100",
                env = "QC_ROBOT_CHARGE_COMPLETE_BATTERY_PERCENT"
        )
        private int chargeCompleteBatteryPercent = 100;

        @ConfigOption(
                path = "simulation.charge-rate-percent-per-second",
                description = "Battery percentage increase per second while the mock robot is charging.",
                defaultValue = "100.0",
                env = "QC_ROBOT_CHARGE_RATE_PERCENT_PER_SECOND"
        )
        private double chargeRatePercentPerSecond = 100.0;

        public boolean isAutoComplete() {
            return autoComplete;
        }

        public void setAutoComplete(boolean autoComplete) {
            this.autoComplete = autoComplete;
        }

        public long getTaskCompleteDelayMs() {
            return taskCompleteDelayMs;
        }

        public void setTaskCompleteDelayMs(long taskCompleteDelayMs) {
            this.taskCompleteDelayMs = taskCompleteDelayMs;
        }

        public List<String> getCompleteOnStartTaskTypes() {
            return completeOnStartTaskTypes;
        }

        public void setCompleteOnStartTaskTypes(List<String> completeOnStartTaskTypes) {
            this.completeOnStartTaskTypes = completeOnStartTaskTypes == null
                    ? new ArrayList<>()
                    : new ArrayList<>(completeOnStartTaskTypes);
        }

        public boolean isFailNavigation() {
            return failNavigation;
        }

        public void setFailNavigation(boolean failNavigation) {
            this.failNavigation = failNavigation;
        }

        public boolean isRejectNavigation() {
            return rejectNavigation;
        }

        public void setRejectNavigation(boolean rejectNavigation) {
            this.rejectNavigation = rejectNavigation;
        }

        public boolean isRejectActivity() {
            return rejectActivity;
        }

        public void setRejectActivity(boolean rejectActivity) {
            this.rejectActivity = rejectActivity;
        }

        public boolean isPublishBodyStatusReport() {
            return publishBodyStatusReport;
        }

        public void setPublishBodyStatusReport(boolean publishBodyStatusReport) {
            this.publishBodyStatusReport = publishBodyStatusReport;
        }

        public boolean isPublishLegacyStatusReport() {
            return publishLegacyStatusReport;
        }

        public void setPublishLegacyStatusReport(boolean publishLegacyStatusReport) {
            this.publishLegacyStatusReport = publishLegacyStatusReport;
        }

        public int getInitialBatteryPercent() {
            return initialBatteryPercent;
        }

        public void setInitialBatteryPercent(int initialBatteryPercent) {
            this.initialBatteryPercent = initialBatteryPercent;
        }

        public long getActivityCompleteDelayMs() {
            return activityCompleteDelayMs;
        }

        public void setActivityCompleteDelayMs(long activityCompleteDelayMs) {
            this.activityCompleteDelayMs = activityCompleteDelayMs;
        }

        public int getChargeCompleteBatteryPercent() {
            return chargeCompleteBatteryPercent;
        }

        public void setChargeCompleteBatteryPercent(int chargeCompleteBatteryPercent) {
            this.chargeCompleteBatteryPercent = chargeCompleteBatteryPercent;
        }

        public double getChargeRatePercentPerSecond() {
            return chargeRatePercentPerSecond;
        }

        public void setChargeRatePercentPerSecond(double chargeRatePercentPerSecond) {
            this.chargeRatePercentPerSecond = chargeRatePercentPerSecond;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MotionConfig {
        @ConfigOption(
                path = "motion.frame-id",
                description = "Coordinate frame id reported in body status position. For map6 real adapter this should be L1.",
                defaultValue = "L1",
                env = "QC_ROBOT_MOTION_FRAME_ID"
        )
        private String frameId = "L1";

        @ConfigOption(
                path = "motion.initial-x",
                description = "Initial robot-coordinate X. Default is map6 real Goal_qZgch transformed by qc_robot.map6.real.yaml.",
                defaultValue = "-0.032172342004435",
                env = "QC_ROBOT_INITIAL_X"
        )
        private double initialX = -0.032172342004435;

        @ConfigOption(
                path = "motion.initial-y",
                description = "Initial robot-coordinate Y. Default is map6 real Goal_qZgch transformed by qc_robot.map6.real.yaml.",
                defaultValue = "0.026406493244599",
                env = "QC_ROBOT_INITIAL_Y"
        )
        private double initialY = 0.026406493244599;

        @ConfigOption(
                path = "motion.initial-yaw",
                description = "Initial robot-coordinate yaw in radians.",
                defaultValue = "0.0",
                env = "QC_ROBOT_INITIAL_YAW"
        )
        private double initialYaw = 0.0;

        @ConfigOption(
                path = "motion.linear-speed-mps",
                description = "Linear speed used to advance mock robot coordinates toward a navigate target.",
                defaultValue = "5.0",
                env = "QC_ROBOT_LINEAR_SPEED_MPS"
        )
        private double linearSpeedMps = 5.0;

        @ConfigOption(
                path = "motion.angular-speed-radps",
                description = "Angular speed used to advance yaw toward a target yaw.",
                defaultValue = "6.0",
                env = "QC_ROBOT_ANGULAR_SPEED_RADPS"
        )
        private double angularSpeedRadps = 6.0;

        @ConfigOption(
                path = "motion.arrival-tolerance",
                description = "Distance in robot coordinates considered arrived for navigation, charge and return-home.",
                defaultValue = "0.05",
                env = "QC_ROBOT_ARRIVAL_TOLERANCE"
        )
        private double arrivalTolerance = 0.05;

        @ConfigOption(
                path = "motion.home-x",
                description = "Robot-coordinate home X for return_home.",
                defaultValue = "-0.032172342004435",
                env = "QC_ROBOT_HOME_X"
        )
        private double homeX = -0.032172342004435;

        @ConfigOption(
                path = "motion.home-y",
                description = "Robot-coordinate home Y for return_home.",
                defaultValue = "0.026406493244599",
                env = "QC_ROBOT_HOME_Y"
        )
        private double homeY = 0.026406493244599;

        @ConfigOption(
                path = "motion.home-yaw",
                description = "Robot-coordinate home yaw for return_home.",
                defaultValue = "0.0",
                env = "QC_ROBOT_HOME_YAW"
        )
        private double homeYaw = 0.0;

        @ConfigOption(
                path = "motion.charger-x",
                description = "Robot-coordinate charger X for charge and replace_battery.",
                defaultValue = "-0.032172342004435",
                env = "QC_ROBOT_CHARGER_X"
        )
        private double chargerX = -0.032172342004435;

        @ConfigOption(
                path = "motion.charger-y",
                description = "Robot-coordinate charger Y for charge and replace_battery.",
                defaultValue = "0.026406493244599",
                env = "QC_ROBOT_CHARGER_Y"
        )
        private double chargerY = 0.026406493244599;

        @ConfigOption(
                path = "motion.charger-yaw",
                description = "Robot-coordinate charger yaw for charge and replace_battery.",
                defaultValue = "0.0",
                env = "QC_ROBOT_CHARGER_YAW"
        )
        private double chargerYaw = 0.0;

        public String getFrameId() {
            return frameId;
        }

        public void setFrameId(String frameId) {
            this.frameId = frameId;
        }

        public double getInitialX() {
            return initialX;
        }

        public void setInitialX(double initialX) {
            this.initialX = initialX;
        }

        public double getInitialY() {
            return initialY;
        }

        public void setInitialY(double initialY) {
            this.initialY = initialY;
        }

        public double getInitialYaw() {
            return initialYaw;
        }

        public void setInitialYaw(double initialYaw) {
            this.initialYaw = initialYaw;
        }

        public double getLinearSpeedMps() {
            return linearSpeedMps;
        }

        public void setLinearSpeedMps(double linearSpeedMps) {
            this.linearSpeedMps = linearSpeedMps;
        }

        public double getAngularSpeedRadps() {
            return angularSpeedRadps;
        }

        public void setAngularSpeedRadps(double angularSpeedRadps) {
            this.angularSpeedRadps = angularSpeedRadps;
        }

        public double getArrivalTolerance() {
            return arrivalTolerance;
        }

        public void setArrivalTolerance(double arrivalTolerance) {
            this.arrivalTolerance = arrivalTolerance;
        }

        public double getHomeX() {
            return homeX;
        }

        public void setHomeX(double homeX) {
            this.homeX = homeX;
        }

        public double getHomeY() {
            return homeY;
        }

        public void setHomeY(double homeY) {
            this.homeY = homeY;
        }

        public double getHomeYaw() {
            return homeYaw;
        }

        public void setHomeYaw(double homeYaw) {
            this.homeYaw = homeYaw;
        }

        public double getChargerX() {
            return chargerX;
        }

        public void setChargerX(double chargerX) {
            this.chargerX = chargerX;
        }

        public double getChargerY() {
            return chargerY;
        }

        public void setChargerY(double chargerY) {
            this.chargerY = chargerY;
        }

        public double getChargerYaw() {
            return chargerYaw;
        }

        public void setChargerYaw(double chargerYaw) {
            this.chargerYaw = chargerYaw;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MapsConfig {
        @ConfigOption(
                path = "maps.current-map-name",
                description = "Current map name exposed by mapping/current and status payloads.",
                defaultValue = "L1",
                env = "QC_ROBOT_CURRENT_MAP_NAME"
        )
        private String currentMapName = "L1";

        @ConfigOption(
                path = "maps.active-profile",
                description = "Optional named map profile that overrides frame, initial, home and charger robot-coordinate poses.",
                defaultValue = "map6-real",
                env = "QC_ROBOT_MAP_PROFILE"
        )
        private String activeProfile = "map6-real";

        @ConfigOption(
                path = "maps.entries",
                description = "Configured maps exposed by mapping/list and mapping/info.",
                defaultValue = "MAP6-L1/L1"
        )
        private List<MapEntry> entries = new ArrayList<>(List.of(
                new MapEntry("MAP6-L1", "L1", Map.of(
                        "scene", "map6",
                        "profile", "real",
                        "charger_waypoint", "Goal_qZgch",
                        "home_waypoint", "Goal_qZgch"
                ))
        ));

        @ConfigOption(
                path = "maps.profiles",
                description = "Named robot-coordinate map profiles. Use a profile when switching map scenes without code changes.",
                defaultValue = "map6-real"
        )
        private Map<String, MapProfile> profiles = new LinkedHashMap<>(Map.of(
                "map6-real",
                new MapProfile(
                        "L1",
                        new PoseConfig(-0.032172342004435, 0.026406493244599, 0.0),
                        new PoseConfig(-0.032172342004435, 0.026406493244599, 0.0),
                        Map.of("Goal_qZgch", new PoseConfig(-0.032172342004435, 0.026406493244599, 0.0)),
                        Map.of(
                                "Goal_qZgch", new PoseConfig(-0.032172342004435, 0.026406493244599, 0.0),
                                "Goal_lMk0s", new PoseConfig(6.53996890828796, -2.4705697116354166, 1.5707963267948966),
                                "Goal_YGjWE", new PoseConfig(6.53996890828796, 0.326043637830203, 1.5707963267948966)
                        )
                )
        ));

        public String getCurrentMapName() {
            return currentMapName;
        }

        public void setCurrentMapName(String currentMapName) {
            this.currentMapName = currentMapName;
        }

        public String getActiveProfile() {
            return activeProfile;
        }

        public void setActiveProfile(String activeProfile) {
            this.activeProfile = activeProfile;
        }

        public List<MapEntry> getEntries() {
            return entries;
        }

        public void setEntries(List<MapEntry> entries) {
            this.entries = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
        }

        public Map<String, MapProfile> getProfiles() {
            return profiles;
        }

        public void setProfiles(Map<String, MapProfile> profiles) {
            this.profiles = profiles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(profiles);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MapProfile {
        private String frameId;
        private PoseConfig initial;
        private PoseConfig home;
        private Map<String, PoseConfig> chargers = new LinkedHashMap<>();
        private Map<String, PoseConfig> waypoints = new LinkedHashMap<>();

        public MapProfile() {
        }

        public MapProfile(String frameId, PoseConfig initial, PoseConfig home, Map<String, PoseConfig> chargers) {
            this(frameId, initial, home, chargers, null);
        }

        public MapProfile(
                String frameId,
                PoseConfig initial,
                PoseConfig home,
                Map<String, PoseConfig> chargers,
                Map<String, PoseConfig> waypoints
        ) {
            this.frameId = frameId;
            this.initial = initial;
            this.home = home;
            setChargers(chargers);
            setWaypoints(waypoints);
        }

        public String getFrameId() {
            return frameId;
        }

        public void setFrameId(String frameId) {
            this.frameId = frameId;
        }

        public PoseConfig getInitial() {
            return initial;
        }

        public void setInitial(PoseConfig initial) {
            this.initial = initial;
        }

        public PoseConfig getHome() {
            return home;
        }

        public void setHome(PoseConfig home) {
            this.home = home;
        }

        public Map<String, PoseConfig> getChargers() {
            return chargers;
        }

        public void setChargers(Map<String, PoseConfig> chargers) {
            this.chargers = chargers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(chargers);
        }

        public Map<String, PoseConfig> getWaypoints() {
            return waypoints;
        }

        public void setWaypoints(Map<String, PoseConfig> waypoints) {
            this.waypoints = waypoints == null ? new LinkedHashMap<>() : new LinkedHashMap<>(waypoints);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PoseConfig {
        private double x;
        private double y;
        private double yaw;

        public PoseConfig() {
        }

        public PoseConfig(double x, double y, double yaw) {
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileIoConfig {
        @ConfigOption(
                path = "file-io.enabled",
                description = "Whether deep closed-loop file IO is enabled for template download, map upload/download and result package upload.",
                defaultValue = "true",
                env = "QC_ROBOT_FILE_IO_ENABLED"
        )
        private boolean enabled = true;

        @ConfigOption(
                path = "file-io.storage-dir",
                description = "Local runtime directory used to store downloaded templates, downloaded maps, generated fallback map zips and task result zips.",
                defaultValue = "target/qcrobotmock-runtime",
                env = "QC_ROBOT_FILE_IO_STORAGE_DIR"
        )
        private String storageDir = "target/qcrobotmock-runtime";

        @ConfigOption(
                path = "file-io.template-download-required",
                description = "When true, task_template/add fails if template_file_URL is blank or cannot be downloaded.",
                defaultValue = "true",
                env = "QC_ROBOT_TEMPLATE_DOWNLOAD_REQUIRED"
        )
        private boolean templateDownloadRequired = true;

        @ConfigOption(
                path = "file-io.map-upload-required",
                description = "When true, mapping/get fails unless the configured map zip is uploaded to upload_url successfully.",
                defaultValue = "true",
                env = "QC_ROBOT_MAP_UPLOAD_REQUIRED"
        )
        private boolean mapUploadRequired = true;

        @ConfigOption(
                path = "file-io.map-download-required",
                description = "When true, mapping/download fails if map_url is blank or cannot be downloaded.",
                defaultValue = "true",
                env = "QC_ROBOT_MAP_DOWNLOAD_REQUIRED"
        )
        private boolean mapDownloadRequired = true;

        @ConfigOption(
                path = "file-io.result-upload-required",
                description = "When true, task/result_files_upload_start fails unless the configured result file is uploaded to path_url successfully.",
                defaultValue = "true",
                env = "QC_ROBOT_RESULT_UPLOAD_REQUIRED"
        )
        private boolean resultUploadRequired = true;

        @ConfigOption(
                path = "file-io.result-upload-file-path",
                description = "Local file uploaded when task/result_files_upload_start is received.",
                defaultValue = "config/VID_20260410_202412.mp4",
                env = "QC_ROBOT_RESULT_UPLOAD_FILE_PATH"
        )
        private String resultUploadFilePath = "config/VID_20260410_202412.mp4";

        @ConfigOption(
                path = "file-io.map-upload-file-path",
                description = "Local zip file uploaded when mapping/get is received. When blank, the mock generates a map package.",
                defaultValue = "config/mock-map.zip",
                env = "QC_ROBOT_MAP_UPLOAD_FILE_PATH"
        )
        private String mapUploadFilePath = "config/mock-map.zip";

        @ConfigOption(
                path = "file-io.default-map-zip-name",
                description = "File name used when the mock generates a map package for mapping/get.",
                defaultValue = "map5.zip",
                env = "QC_ROBOT_DEFAULT_MAP_ZIP_NAME"
        )
        private String defaultMapZipName = "map5.zip";

        @ConfigOption(
                path = "file-io.http-timeout-ms",
                description = "HTTP timeout for template/map download and map/result upload operations.",
                defaultValue = "10000",
                env = "QC_ROBOT_FILE_IO_HTTP_TIMEOUT_MS"
        )
        private long httpTimeoutMs = 10000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getStorageDir() {
            return storageDir;
        }

        public void setStorageDir(String storageDir) {
            this.storageDir = storageDir;
        }

        public boolean isTemplateDownloadRequired() {
            return templateDownloadRequired;
        }

        public void setTemplateDownloadRequired(boolean templateDownloadRequired) {
            this.templateDownloadRequired = templateDownloadRequired;
        }

        public boolean isMapUploadRequired() {
            return mapUploadRequired;
        }

        public void setMapUploadRequired(boolean mapUploadRequired) {
            this.mapUploadRequired = mapUploadRequired;
        }

        public boolean isMapDownloadRequired() {
            return mapDownloadRequired;
        }

        public void setMapDownloadRequired(boolean mapDownloadRequired) {
            this.mapDownloadRequired = mapDownloadRequired;
        }

        public boolean isResultUploadRequired() {
            return resultUploadRequired;
        }

        public void setResultUploadRequired(boolean resultUploadRequired) {
            this.resultUploadRequired = resultUploadRequired;
        }

        public String getResultUploadFilePath() {
            return resultUploadFilePath;
        }

        public void setResultUploadFilePath(String resultUploadFilePath) {
            this.resultUploadFilePath = resultUploadFilePath;
        }

        public String getMapUploadFilePath() {
            return mapUploadFilePath;
        }

        public void setMapUploadFilePath(String mapUploadFilePath) {
            this.mapUploadFilePath = mapUploadFilePath;
        }

        public String getDefaultMapZipName() {
            return defaultMapZipName;
        }

        public void setDefaultMapZipName(String defaultMapZipName) {
            this.defaultMapZipName = defaultMapZipName;
        }

        public long getHttpTimeoutMs() {
            return httpTimeoutMs;
        }

        public void setHttpTimeoutMs(long httpTimeoutMs) {
            this.httpTimeoutMs = httpTimeoutMs;
        }
    }

    public record MapEntry(
            @JsonProperty("map_id") String mapId,
            @JsonProperty("map_name") String mapName,
            @JsonProperty("metadata") Map<String, Object> metadata
    ) {
        public MapEntry {
            metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SimAgvConfig {
        @ConfigOption(
                path = "sim-agv.enabled",
                description = "Connect to the simulated AGV rosbridge WebSocket and ingest live telemetry (state, battery, map-frame pose).",
                defaultValue = "false",
                env = "SIM_AGV_ENABLED"
        )
        private boolean enabled = false;

        @ConfigOption(
                path = "sim-agv.ws-url",
                description = "rosbridge WebSocket URL exposed by agv_bridge_v2 (docker default port is 9090).",
                defaultValue = "ws://127.0.0.1:9090",
                env = "SIM_AGV_WS_URL"
        )
        private String wsUrl = "ws://127.0.0.1:9090";

        @ConfigOption(
                path = "sim-agv.status-topic",
                description = "AgvStatus topic published by agv_nav_server at 1Hz.",
                defaultValue = "/agv/status",
                env = "SIM_AGV_STATUS_TOPIC"
        )
        private String statusTopic = "/agv/status";

        @ConfigOption(
                path = "sim-agv.tf-topic",
                description = "TF topic carrying the map-><agv_id>/base_link broadcast (10Hz).",
                defaultValue = "/tf",
                env = "SIM_AGV_TF_TOPIC"
        )
        private String tfTopic = "/tf";

        @ConfigOption(
                path = "sim-agv.odom-topic",
                description = "Optional odometry topic for velocities and odom-frame pose.",
                defaultValue = "/odom",
                env = "SIM_AGV_ODOM_TOPIC"
        )
        private String odomTopic = "/odom";

        @ConfigOption(
                path = "sim-agv.odom-enabled",
                description = "Whether to also subscribe the odometry topic.",
                defaultValue = "false",
                env = "SIM_AGV_ODOM_ENABLED"
        )
        private boolean odomEnabled = false;

        @ConfigOption(
                path = "sim-agv.tf-child-frame",
                description = "child_frame_id filter used to pick the AGV pose from /tf. Must match agv_id of the bridge (agv_id + /base_link).",
                defaultValue = "AGV001/base_link",
                env = "SIM_AGV_TF_CHILD_FRAME"
        )
        private String tfChildFrame = "AGV001/base_link";

        @ConfigOption(
                path = "sim-agv.tf-throttle-ms",
                description = "rosbridge throttle_rate (ms) applied to the /tf subscription.",
                defaultValue = "100",
                env = "SIM_AGV_TF_THROTTLE_MS"
        )
        private int tfThrottleMs = 100;

        @ConfigOption(
                path = "sim-agv.reconnect-delay-ms",
                description = "Delay before reconnecting after the rosbridge WebSocket drops.",
                defaultValue = "3000",
                env = "SIM_AGV_RECONNECT_DELAY_MS"
        )
        private long reconnectDelayMs = 3000L;

        @ConfigOption(
                path = "sim-agv.scan-enabled",
                description = "Subscribe the 2D lidar topics (/scan_1, /scan_2) so telemetry covers lidar too.",
                defaultValue = "true",
                env = "SIM_AGV_SCAN_ENABLED"
        )
        private boolean scanEnabled = true;

        @ConfigOption(
                path = "sim-agv.scan-topic-1",
                description = "Front 2D lidar topic.",
                defaultValue = "/scan_1",
                env = "SIM_AGV_SCAN_TOPIC_1"
        )
        private String scanTopic1 = "/scan_1";

        @ConfigOption(
                path = "sim-agv.scan-topic-2",
                description = "Rear 2D lidar topic.",
                defaultValue = "/scan_2",
                env = "SIM_AGV_SCAN_TOPIC_2"
        )
        private String scanTopic2 = "/scan_2";

        @ConfigOption(
                path = "sim-agv.pose-topic",
                description = "Dedicated map-frame PoseStamped topic published by agv_nav_server at 10Hz (not throttled).",
                defaultValue = "/agv/pose",
                env = "SIM_AGV_POSE_TOPIC"
        )
        private String poseTopic = "/agv/pose";

        @ConfigOption(
                path = "sim-agv.scan-throttle-ms",
                description = "rosbridge throttle_rate (ms) applied to the lidar subscriptions (333ms ~ 3Hz).",
                defaultValue = "333",
                env = "SIM_AGV_SCAN_THROTTLE_MS"
        )
        private int scanThrottleMs = 333;

        @ConfigOption(
                path = "sim-agv.summary-interval-ms",
                description = "Period of the INFO telemetry summary log (status/pose/scan heartbeat).",
                defaultValue = "5000",
                env = "SIM_AGV_SUMMARY_INTERVAL_MS"
        )
        private long summaryIntervalMs = 5000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getWsUrl() {
            return wsUrl;
        }

        public void setWsUrl(String wsUrl) {
            this.wsUrl = wsUrl;
        }

        public String getStatusTopic() {
            return statusTopic;
        }

        public void setStatusTopic(String statusTopic) {
            this.statusTopic = statusTopic;
        }

        public String getTfTopic() {
            return tfTopic;
        }

        public void setTfTopic(String tfTopic) {
            this.tfTopic = tfTopic;
        }

        public String getOdomTopic() {
            return odomTopic;
        }

        public void setOdomTopic(String odomTopic) {
            this.odomTopic = odomTopic;
        }

        public boolean isOdomEnabled() {
            return odomEnabled;
        }

        public void setOdomEnabled(boolean odomEnabled) {
            this.odomEnabled = odomEnabled;
        }

        public String getTfChildFrame() {
            return tfChildFrame;
        }

        public void setTfChildFrame(String tfChildFrame) {
            this.tfChildFrame = tfChildFrame;
        }

        public int getTfThrottleMs() {
            return tfThrottleMs;
        }

        public void setTfThrottleMs(int tfThrottleMs) {
            this.tfThrottleMs = tfThrottleMs;
        }

        public long getReconnectDelayMs() {
            return reconnectDelayMs;
        }

        public void setReconnectDelayMs(long reconnectDelayMs) {
            this.reconnectDelayMs = reconnectDelayMs;
        }

        public boolean isScanEnabled() {
            return scanEnabled;
        }

        public void setScanEnabled(boolean scanEnabled) {
            this.scanEnabled = scanEnabled;
        }

        public String getScanTopic1() {
            return scanTopic1;
        }

        public void setScanTopic1(String scanTopic1) {
            this.scanTopic1 = scanTopic1;
        }

        public String getScanTopic2() {
            return scanTopic2;
        }

        public void setScanTopic2(String scanTopic2) {
            this.scanTopic2 = scanTopic2;
        }

        public String getPoseTopic() {
            return poseTopic;
        }

        public void setPoseTopic(String poseTopic) {
            this.poseTopic = poseTopic;
        }

        public int getScanThrottleMs() {
            return scanThrottleMs;
        }

        public void setScanThrottleMs(int scanThrottleMs) {
            this.scanThrottleMs = scanThrottleMs;
        }

        public long getSummaryIntervalMs() {
            return summaryIntervalMs;
        }

        public void setSummaryIntervalMs(long summaryIntervalMs) {
            this.summaryIntervalMs = summaryIntervalMs;
        }
    }
}
