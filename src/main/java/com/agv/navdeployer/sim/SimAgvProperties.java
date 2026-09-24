package com.agv.navdeployer.sim;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 仿真 AGV 对接配置（application.yaml 的 sim_agv 前缀，宽松绑定兼容 snake_case）。
 * 上行：遥测话题订阅；下行：follow_edge action / set_control 服务 / 重定位。
 */
@ConfigurationProperties(prefix = "sim-agv")
public class SimAgvProperties {

    private boolean enabled = false;
    private String wsUrl = "ws://127.0.0.1:9090";
    private long reconnectDelayMs = 3000L;
    private long summaryIntervalMs = 5000L;

    private String statusTopic = "/agv/status";
    private String poseTopic = "/agv/pose";
    private String tfTopic = "/tf";
    /** 静态 TF（雷达等传感器安装关系：base_link → laser_frame），点云安装变换的优先来源 */
    private String tfStaticTopic = "/tf_static";
    private String tfChildFrame = "AGV001/base_link";
    private int tfThrottleMs = 100;
    private boolean odomEnabled = false;
    private String odomTopic = "/odom";
    private boolean scanEnabled = true;
    private String scanTopic1 = "/scan_1";
    private String scanTopic2 = "/scan_2";
    private int scanThrottleMs = 333;
    /** 雷达安装朝向偏移（度）：雷达自身坐标系相对 base_link 的旋转角。对装（背靠背）的反装雷达通常为 180。 */
    private double scan1MountYawDeg = 0.0;
    private double scan2MountYawDeg = 0.0;

    private boolean mapEnabled = true;
    private String mapTopic = "/map";
    private int mapThrottleMs = 5000;
    private int mapFragmentSize = 500000;

    private String followEdgeAction = "/agv/follow_edge";
    private String followEdgeActionType = "agv_bridge_v2_interfaces/action/FollowEdge";
    private String setControlService = "/agv/set_control";
    private String initialPoseTopic = "/initialpose";
    private long serviceTimeoutMs = 5000L;
    private long goalTimeoutSeconds = 600L;
    private double defaultMaxSpeed = 0.6;
    private double defaultStep = 0.1;

    // ===== 地图管理服务（agv_bridge_v2 v0.3.0+）=====
    private String listMapsService = "/agv/list_maps";
    private String getMapService = "/agv/get_map";
    private String loadMapService = "/agv/load_map";
    private String startMappingService = "/agv/start_mapping";
    private String saveMapService = "/agv/save_map";

    /** 镜像状态新鲜度阈值：超过该时长未收到 /agv/status 视为 UNKNOWN，拒绝模式类操作。 */
    private long statusFreshMs = 5000L;

    /** 模式任务（切图等）整体截止时间：超时未回到 NAVIGATION 判 FAILED。 */
    private long modeTaskTimeoutMs = 90000L;

    private final List<String> advertiseTopics = new ArrayList<>();

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

    public double getScan1MountYawDeg() {
        return scan1MountYawDeg;
    }

    public void setScan1MountYawDeg(double scan1MountYawDeg) {
        this.scan1MountYawDeg = scan1MountYawDeg;
    }

    public double getScan2MountYawDeg() {
        return scan2MountYawDeg;
    }

    public void setScan2MountYawDeg(double scan2MountYawDeg) {
        this.scan2MountYawDeg = scan2MountYawDeg;
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

    public void setSetControlService(String setControlService) {
        this.setControlService = setControlService;
    }

    public String getInitialPoseTopic() {
        return initialPoseTopic;
    }

    public void setInitialPoseTopic(String initialPoseTopic) {
        this.initialPoseTopic = initialPoseTopic;
    }

    public long getServiceTimeoutMs() {
        return serviceTimeoutMs;
    }

    public void setServiceTimeoutMs(long serviceTimeoutMs) {
        this.serviceTimeoutMs = serviceTimeoutMs;
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
}
