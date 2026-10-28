package com.agv.navdeployer.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 充电任务配置（prefix: charge）。
 * 服务名与语义见机器人侧《电量与充电对接》(docs/battery_api.md)：
 * /agv/set_charging（std_srvs/srv/SetBool）—— data=true 开始充电，data=false 停止充电；
 * 充电真值以 /agv/status 的 charging / charge_connected / battery_data_valid 遥测为准。
 */
@Component
@ConfigurationProperties(prefix = "charge")
public class ChargeProperties {

    /** 机器人侧开始/停止充电服务（std_srvs/srv/SetBool） */
    private String service = "/agv/set_charging";
    /** set_charging 服务调用超时（毫秒） */
    private long serviceTimeoutMs = 5000;
    /** 默认目标电量百分比：达到即认为充电完成（0, 100] */
    private double targetBatteryPct = 100.0;
    /** set_charging 成功后等待遥测确认 charging=true 的超时（毫秒） */
    private long confirmTimeoutMs = 20000;
    /** 到桩后最小停稳等待（毫秒）：机器人侧拒绝运动中的充电请求，到位即调会被拒 */
    private long dockSettleMinMs = 2000;
    /** 停稳等待上限（毫秒）：odom 异常/不可用时的兜底，超过强制尝试 */
    private long dockSettleMaxMs = 15000;
    /** /odom 静止判定线速度阈值（m/s，|vx|/|vy| 均须低于） */
    private double odomQuietLinearMs = 0.03;
    /** /odom 静止判定角速度阈值（rad/s） */
    private double odomQuietAngularRad = 0.05;
    /** 状态轮询周期（毫秒） */
    private long pollIntervalMs = 1000;
    /** 充电中 charging=false / 电量数据无效的持续宽限：超过判失败（毫秒） */
    private long staleGraceMs = 60000;
    /** 充电全流程看门狗（导航+充电总时长上限，毫秒），默认 4h */
    private long maxDurationMs = 14400000L;

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public long getServiceTimeoutMs() {
        return serviceTimeoutMs;
    }

    public void setServiceTimeoutMs(long serviceTimeoutMs) {
        this.serviceTimeoutMs = serviceTimeoutMs;
    }

    public double getTargetBatteryPct() {
        return targetBatteryPct;
    }

    public void setTargetBatteryPct(double targetBatteryPct) {
        this.targetBatteryPct = targetBatteryPct;
    }

    public long getConfirmTimeoutMs() {
        return confirmTimeoutMs;
    }

    public void setConfirmTimeoutMs(long confirmTimeoutMs) {
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    public long getDockSettleMinMs() {
        return dockSettleMinMs;
    }

    public void setDockSettleMinMs(long dockSettleMinMs) {
        this.dockSettleMinMs = dockSettleMinMs;
    }

    public long getDockSettleMaxMs() {
        return dockSettleMaxMs;
    }

    public void setDockSettleMaxMs(long dockSettleMaxMs) {
        this.dockSettleMaxMs = dockSettleMaxMs;
    }

    public double getOdomQuietLinearMs() {
        return odomQuietLinearMs;
    }

    public void setOdomQuietLinearMs(double odomQuietLinearMs) {
        this.odomQuietLinearMs = odomQuietLinearMs;
    }

    public double getOdomQuietAngularRad() {
        return odomQuietAngularRad;
    }

    public void setOdomQuietAngularRad(double odomQuietAngularRad) {
        this.odomQuietAngularRad = odomQuietAngularRad;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public long getStaleGraceMs() {
        return staleGraceMs;
    }

    public void setStaleGraceMs(long staleGraceMs) {
        this.staleGraceMs = staleGraceMs;
    }

    public long getMaxDurationMs() {
        return maxDurationMs;
    }

    public void setMaxDurationMs(long maxDurationMs) {
        this.maxDurationMs = maxDurationMs;
    }
}
