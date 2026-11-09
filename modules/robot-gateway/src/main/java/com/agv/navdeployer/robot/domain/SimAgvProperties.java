package com.agv.navdeployer.robot.domain;

import com.agv.navdeployer.robot.domain.LocalizationOperation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "sim-agv")
public class SimAgvProperties {

    private boolean enabled = false;
    private String wsUrl = "ws://127.0.0.1:9090";
    private long reconnectDelayMs = 3000L;
    private long summaryIntervalMs = 5000L;

    private String statusTopic = "/agv/status";
    private String poseTopic = "/agv/pose";
    private String tfTopic = "/tf";
    private String tfStaticTopic = "/tf_static";
    private String tfChildFrame = "AGV001/base_link";
    private int tfThrottleMs = 100;
    private boolean odomEnabled = false;
    private String odomTopic = "/odom";
    private boolean scanEnabled = true;
    private String scanTopic1 = "/scan_1";
    private String scanTopic2 = "/scan_2";
    private int scanThrottleMs = 333;

    private boolean jointStatesEnabled = true;
    private String jointStatesTopic = "/joint_states";

    private boolean mapEnabled = true;
    private String mapTopic = "/map";
    private int mapThrottleMs = 3000;
    private int mapFragmentSize = 500000;

    // 客户端一连接就订阅，若机器人侧话题还没 advertise（桥接节点阻塞初始化中），
    // rosbridge 类型推断失败且不重试 → 永久失明。显式类型从根上消除该竞态。
    private String statusTopicType = "agv_bridge_v2_interfaces/msg/AgvStatus";
    private String poseTopicType = "geometry_msgs/msg/PoseStamped";
    private String tfTopicType = "tf2_msgs/msg/TFMessage";
    private String odomTopicType = "nav_msgs/msg/Odometry";
    private String scanTopicType = "sensor_msgs/msg/LaserScan";
    private String mapTopicType = "nav_msgs/msg/OccupancyGrid";
    private String jointStatesTopicType = "sensor_msgs/msg/JointState";

    private String followEdgeAction = "/agv/follow_edge";
    private String followEdgeActionType = "agv_bridge_v2_interfaces/action/FollowEdge";
    private String setControlService = "/agv/set_control";
    private String relocalizeService = "/agv/relocalize";
    private String cmdVelTopic = "/cmd_vel";
    private long serviceTimeoutMs = 5000L;
    private boolean wsMessageLogCommandsEnabled = true;
    private boolean wsMessageLogEnabled = true;
    private int wsMessageLogMaxChars = 300;
    private int wsMessageLogFullChars = 65_536;
    private long wsMessageLogTopicIntervalMs = 10_000;
    private long wsMessageLogUnchangedIntervalMs = 60_000;
    private long wsMessageLogSensorIntervalMs = 30_000;
    private long goalTimeoutSeconds = 600L;
    private double defaultMaxSpeed = 0.6;
    private double defaultStep = 0.1;
    private double followPathStartRadius = 0.5;

    private boolean armGateEnabled = true;
    private boolean armStrictUnknown = false;
    private double armHomeToleranceRad = 0.1;
    private double[] armHomeLeft = {0, -2.6, 2.65, -1.62079632679, -1.57079632679, 0};
    private double[] armHomeRight = {0, -2.6, 2.65, -1.62079632679, -1.57079632679, 0};
    private double[] armInspectPose = {0.5, -1.2, 1.0, -0.5, 0, 0};

    private String listMapsService = "/agv/list_maps";
    private String getMapService = "/agv/get_map";
    private String loadMapService = "/agv/load_map";
    private String startMappingService = "/agv/start_mapping";
    private String saveMapService = "/agv/save_map";
    private String cancelMappingService = "/agv/cancel_mapping";

    private String localizationOperationTopic = "/agv/localization_operation";
    private String localizationOperationTopicType = "agv_bridge_v2_interfaces/msg/LocalizationOperation";
    private String getLocalizationOperationService = "/agv/get_localization_operation";
    private long localizationTaskTimeoutMs = 660000L;
    private String armMoveService = "/agv/arm_move";
    // 机器人侧 HTTP 统一入口（robot_http_server 单进程按 URL 路由：/maps/*、/native/*、包资源），替代旧散装端口 8089/8090/8091/8092
    private int robotHttpPort = 8080;

    private long statusFreshMs = 15000L;

    private long modeTaskTimeoutMs = 360000L;

    private final List<String> advertiseTopics = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI robotHttpUri(int port) {
        String host = URI.create(wsUrl).getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("SIM_AGV_WS_URL must include the robot hostname");
        }
        try {
            return new URI("http", null, host, port, null, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid robot HTTP address", e);
        }
    }

    public URI robotHttpUri() {
        return robotHttpUri(robotHttpPort);
    }

    public String getWsUrl() {
        return wsUrl;
    }

    public void setWsUrl(String wsUrl) {
        if (wsUrl != null && !wsUrl.isBlank() && !wsUrl.contains("://")) {
            this.wsUrl = "ws://" + wsUrl.trim();
        } else {
            this.wsUrl = wsUrl;
        }
    }

    public long getReconnectDelayMs() {
        return reconnectDelayMs;
    }

    public void setReconnectDelayMs(long reconnectDelayMs) {
        this.reconnectDelayMs = reconnectDelayMs;
    }

    public long getSummaryIntervalMs() {
        return summaryIntervalMs;
    }

    public void setSummaryIntervalMs(long summaryIntervalMs) {
        this.summaryIntervalMs = summaryIntervalMs;
    }

    public String getStatusTopic() {
        return statusTopic;
    }

    public void setStatusTopic(String statusTopic) {
        this.statusTopic = statusTopic;
    }

    public String getPoseTopic() {
        return poseTopic;
    }

    public void setPoseTopic(String poseTopic) {
        this.poseTopic = poseTopic;
    }

    public String getTfTopic() {
        return tfTopic;
    }

    public void setTfTopic(String tfTopic) {
        this.tfTopic = tfTopic;
    }

    public String getTfStaticTopic() {
        return tfStaticTopic;
    }

    public void setTfStaticTopic(String tfStaticTopic) {
        this.tfStaticTopic = tfStaticTopic;
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

    public boolean isOdomEnabled() {
        return odomEnabled;
    }

    public void setOdomEnabled(boolean odomEnabled) {
        this.odomEnabled = odomEnabled;
    }

    public String getOdomTopic() {
        return odomTopic;
    }

    public void setOdomTopic(String odomTopic) {
        this.odomTopic = odomTopic;
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

    public int getScanThrottleMs() {
        return scanThrottleMs;
    }

    public void setScanThrottleMs(int scanThrottleMs) {
        this.scanThrottleMs = scanThrottleMs;
    }

    public boolean isJointStatesEnabled() {
        return jointStatesEnabled;
    }

    public void setJointStatesEnabled(boolean jointStatesEnabled) {
        this.jointStatesEnabled = jointStatesEnabled;
    }

    public String getJointStatesTopic() {
        return jointStatesTopic;
    }

    public void setJointStatesTopic(String jointStatesTopic) {
        this.jointStatesTopic = jointStatesTopic;
    }

    public String getJointStatesTopicType() {
        return jointStatesTopicType;
    }

    public void setJointStatesTopicType(String jointStatesTopicType) {
        this.jointStatesTopicType = jointStatesTopicType;
    }

    public boolean isMapEnabled() {
        return mapEnabled;
    }

    public void setMapEnabled(boolean mapEnabled) {
        this.mapEnabled = mapEnabled;
    }

    public String getMapTopic() {
        return mapTopic;
    }

    public void setMapTopic(String mapTopic) {
        this.mapTopic = mapTopic;
    }

    public int getMapThrottleMs() {
        return mapThrottleMs;
    }

    public void setMapThrottleMs(int mapThrottleMs) {
        this.mapThrottleMs = mapThrottleMs;
    }

    public int getMapFragmentSize() {
        return mapFragmentSize;
    }

    public void setMapFragmentSize(int mapFragmentSize) {
        this.mapFragmentSize = mapFragmentSize;
    }

    public String getStatusTopicType() {
        return statusTopicType;
    }

    public void setStatusTopicType(String statusTopicType) {
        this.statusTopicType = statusTopicType;
    }

    public String getPoseTopicType() {
        return poseTopicType;
    }

    public void setPoseTopicType(String poseTopicType) {
        this.poseTopicType = poseTopicType;
    }

    public String getTfTopicType() {
        return tfTopicType;
    }

    public void setTfTopicType(String tfTopicType) {
        this.tfTopicType = tfTopicType;
    }

    public String getOdomTopicType() {
        return odomTopicType;
    }

    public void setOdomTopicType(String odomTopicType) {
        this.odomTopicType = odomTopicType;
    }

    public String getScanTopicType() {
        return scanTopicType;
    }

    public void setScanTopicType(String scanTopicType) {
        this.scanTopicType = scanTopicType;
    }

    public String getMapTopicType() {
        return mapTopicType;
    }

    public void setMapTopicType(String mapTopicType) {
        this.mapTopicType = mapTopicType;
    }

    public String getFollowEdgeAction() {
        return followEdgeAction;
    }

    public void setFollowEdgeAction(String followEdgeAction) {
        this.followEdgeAction = followEdgeAction;
    }

    public String getFollowEdgeActionType() {
        return followEdgeActionType;
    }

    public void setFollowEdgeActionType(String followEdgeActionType) {
        this.followEdgeActionType = followEdgeActionType;
    }

    public String getSetControlService() {
        return setControlService;
    }

    public String getRelocalizeService() {
        return relocalizeService;
    }

    public void setRelocalizeService(String relocalizeService) {
        this.relocalizeService = relocalizeService;
    }

    public void setSetControlService(String setControlService) {
        this.setControlService = setControlService;
    }

    public String getCmdVelTopic() {
        return cmdVelTopic;
    }

    public void setCmdVelTopic(String cmdVelTopic) {
        this.cmdVelTopic = cmdVelTopic;
    }

    public long getServiceTimeoutMs() {
        return serviceTimeoutMs;
    }

    public void setServiceTimeoutMs(long serviceTimeoutMs) {
        this.serviceTimeoutMs = serviceTimeoutMs;
    }

    public boolean isWsMessageLogEnabled() {
        return wsMessageLogEnabled;
    }

    public void setWsMessageLogEnabled(boolean wsMessageLogEnabled) {
        this.wsMessageLogEnabled = wsMessageLogEnabled;
    }

    public boolean isWsMessageLogCommandsEnabled() {
        return wsMessageLogCommandsEnabled;
    }

    public void setWsMessageLogCommandsEnabled(boolean wsMessageLogCommandsEnabled) {
        this.wsMessageLogCommandsEnabled = wsMessageLogCommandsEnabled;
    }

    public int getWsMessageLogMaxChars() {
        return wsMessageLogMaxChars;
    }

    public void setWsMessageLogMaxChars(int wsMessageLogMaxChars) {
        this.wsMessageLogMaxChars = wsMessageLogMaxChars;
    }

    public int getWsMessageLogFullChars() {
        return wsMessageLogFullChars;
    }

    public void setWsMessageLogFullChars(int wsMessageLogFullChars) {
        this.wsMessageLogFullChars = wsMessageLogFullChars;
    }

    public long getWsMessageLogTopicIntervalMs() {
        return wsMessageLogTopicIntervalMs;
    }

    public void setWsMessageLogTopicIntervalMs(long wsMessageLogTopicIntervalMs) {
        this.wsMessageLogTopicIntervalMs = wsMessageLogTopicIntervalMs;
    }

    public long getWsMessageLogUnchangedIntervalMs() {
        return wsMessageLogUnchangedIntervalMs;
    }

    public void setWsMessageLogUnchangedIntervalMs(long wsMessageLogUnchangedIntervalMs) {
        this.wsMessageLogUnchangedIntervalMs = wsMessageLogUnchangedIntervalMs;
    }

    public long getWsMessageLogSensorIntervalMs() {
        return wsMessageLogSensorIntervalMs;
    }

    public void setWsMessageLogSensorIntervalMs(long wsMessageLogSensorIntervalMs) {
        this.wsMessageLogSensorIntervalMs = wsMessageLogSensorIntervalMs;
    }

    public long getGoalTimeoutSeconds() {
        return goalTimeoutSeconds;
    }

    public void setGoalTimeoutSeconds(long goalTimeoutSeconds) {
        this.goalTimeoutSeconds = goalTimeoutSeconds;
    }

    public double getDefaultMaxSpeed() {
        return defaultMaxSpeed;
    }

    public void setDefaultMaxSpeed(double defaultMaxSpeed) {
        this.defaultMaxSpeed = defaultMaxSpeed;
    }

    public double getDefaultStep() {
        return defaultStep;
    }

    public void setDefaultStep(double defaultStep) {
        this.defaultStep = defaultStep;
    }

    public double getFollowPathStartRadius() {
        return followPathStartRadius;
    }

    public void setFollowPathStartRadius(double followPathStartRadius) {
        this.followPathStartRadius = followPathStartRadius;
    }

    public boolean isArmGateEnabled() {
        return armGateEnabled;
    }

    public void setArmGateEnabled(boolean armGateEnabled) {
        this.armGateEnabled = armGateEnabled;
    }

    public boolean isArmStrictUnknown() {
        return armStrictUnknown;
    }

    public void setArmStrictUnknown(boolean armStrictUnknown) {
        this.armStrictUnknown = armStrictUnknown;
    }

    public double getArmHomeToleranceRad() {
        return armHomeToleranceRad;
    }

    public void setArmHomeToleranceRad(double armHomeToleranceRad) {
        this.armHomeToleranceRad = armHomeToleranceRad;
    }

    public double[] getArmHomeLeft() {
        return armHomeLeft;
    }

    public void setArmHomeLeft(double[] armHomeLeft) {
        this.armHomeLeft = armHomeLeft;
    }

    public double[] getArmHomeRight() {
        return armHomeRight;
    }

    public void setArmHomeRight(double[] armHomeRight) {
        this.armHomeRight = armHomeRight;
    }

    public double[] getArmInspectPose() {
        return armInspectPose;
    }

    public void setArmInspectPose(double[] armInspectPose) {
        this.armInspectPose = armInspectPose;
    }

    public List<String> getAdvertiseTopics() {
        return advertiseTopics;
    }

    public String getListMapsService() {
        return listMapsService;
    }

    public void setListMapsService(String listMapsService) {
        this.listMapsService = listMapsService;
    }

    public String getGetMapService() {
        return getMapService;
    }

    public void setGetMapService(String getMapService) {
        this.getMapService = getMapService;
    }

    public String getLoadMapService() {
        return loadMapService;
    }

    public void setLoadMapService(String loadMapService) {
        this.loadMapService = loadMapService;
    }

    public String getStartMappingService() {
        return startMappingService;
    }

    public void setStartMappingService(String startMappingService) {
        this.startMappingService = startMappingService;
    }

    public String getSaveMapService() {
        return saveMapService;
    }

    public void setSaveMapService(String saveMapService) {
        this.saveMapService = saveMapService;
    }

    public String getCancelMappingService() {
        return cancelMappingService;
    }

    public void setCancelMappingService(String cancelMappingService) {
        this.cancelMappingService = cancelMappingService;
    }

    public String getArmMoveService() {
        return armMoveService;
    }

    public void setArmMoveService(String armMoveService) {
        this.armMoveService = armMoveService;
    }

    public int getRobotHttpPort() {
        return robotHttpPort;
    }

    public void setRobotHttpPort(int robotHttpPort) {
        this.robotHttpPort = robotHttpPort;
    }

    public long getStatusFreshMs() {
        return statusFreshMs;
    }

    public void setStatusFreshMs(long statusFreshMs) {
        this.statusFreshMs = statusFreshMs;
    }

    public long getModeTaskTimeoutMs() {
        return modeTaskTimeoutMs;
    }

    public void setModeTaskTimeoutMs(long modeTaskTimeoutMs) {
        this.modeTaskTimeoutMs = modeTaskTimeoutMs;
    }

    public String getLocalizationOperationTopic() {
        return localizationOperationTopic;
    }

    public void setLocalizationOperationTopic(String localizationOperationTopic) {
        this.localizationOperationTopic = localizationOperationTopic;
    }

    public String getLocalizationOperationTopicType() {
        return localizationOperationTopicType;
    }

    public void setLocalizationOperationTopicType(String localizationOperationTopicType) {
        this.localizationOperationTopicType = localizationOperationTopicType;
    }

    public String getGetLocalizationOperationService() {
        return getLocalizationOperationService;
    }

    public void setGetLocalizationOperationService(String getLocalizationOperationService) {
        this.getLocalizationOperationService = getLocalizationOperationService;
    }

    public long getLocalizationTaskTimeoutMs() {
        return localizationTaskTimeoutMs;
    }

    public void setLocalizationTaskTimeoutMs(long localizationTaskTimeoutMs) {
        this.localizationTaskTimeoutMs = localizationTaskTimeoutMs;
    }
}
