package com.agv.navdeployer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RobotOperationalService {

    private static final Logger log = LoggerFactory.getLogger(RobotOperationalService.class);

    private volatile boolean decommissioned = false;
    private volatile String reason = "";

    public boolean isDecommissioned() {
        return decommissioned;
    }

    public String getReason() {
        return reason;
    }

    public void decommission(String reason) {
        this.decommissioned = true;
        this.reason = reason == null || reason.isBlank() ? "maintenance" : reason.trim();
        log.warn("机器人已停用（decommissioned）: reason={}", this.reason);
    }

    public void recommission() {
        log.info("机器人恢复可用（recommissioned），此前原因: {}", this.reason);
        this.decommissioned = false;
        this.reason = "";
    }

    public void requireOperational() {
        if (decommissioned) {
            throw new IllegalStateException("机器人已停用（" + reason + "），不接收新任务");
        }
    }
}
