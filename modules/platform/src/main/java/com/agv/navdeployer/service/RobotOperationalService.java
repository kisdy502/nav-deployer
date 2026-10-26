package com.agv.navdeployer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 机器人运营状态：停用（维修中/退役）开关。
 * 停用后：RMS 不再接新任务、状态上报标记异常、上位机 UI 可视。
 * 运行时状态（重启后恢复可用），不持久化。
 */
@Service
public class RobotOperationalService {

    private static final Logger log = LoggerFactory.getLogger(RobotOperationalService.class);

    private volatile boolean decommissioned = false;
    private volatile String reason = "";

    /** 是否停用（维修中/退役）。 */
    public boolean isDecommissioned() {
        return decommissioned;
    }

    /** 停用原因（维修中/退役/故障等，上报给 RMS）。 */
    public String getReason() {
        return reason;
    }

    /** 停用（拒接新任务，状态上报标记异常）。 */
    public void decommission(String reason) {
        this.decommissioned = true;
        this.reason = reason == null || reason.isBlank() ? "maintenance" : reason.trim();
        log.warn("机器人已停用（decommissioned）: reason={}", this.reason);
    }

    /** 恢复可用。 */
    public void recommission() {
        log.info("机器人恢复可用（recommissioned），此前原因: {}", this.reason);
        this.decommissioned = false;
        this.reason = "";
    }

    /** 任务受理前校验：停用时抛出。 */
    public void requireOperational() {
        if (decommissioned) {
            throw new IllegalStateException("机器人已停用（" + reason + "），不接收新任务");
        }
    }
}
