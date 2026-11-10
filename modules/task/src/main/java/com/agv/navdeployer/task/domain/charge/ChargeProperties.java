package com.agv.navdeployer.task.domain.charge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "charge")
public class ChargeProperties {

    private String service = "/agv/set_charging";
    private long serviceTimeoutMs = 5000;
    private double targetBatteryPct = 100.0;
    private long confirmTimeoutMs = 20000;
    private long dockSettleMinMs = 2000;
    private long dockSettleMaxMs = 15000;
    private double odomQuietLinearMs = 0.03;
    private double odomQuietAngularRad = 0.05;
    private long pollIntervalMs = 1000;
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

    public long getMaxDurationMs() {
        return maxDurationMs;
    }

    public void setMaxDurationMs(long maxDurationMs) {
        this.maxDurationMs = maxDurationMs;
    }
}
