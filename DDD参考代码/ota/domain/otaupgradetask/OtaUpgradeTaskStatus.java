package com.zioneer.robotmanager.robot.ota.domain.otaupgradetask;

import lombok.Getter;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

@Getter
public enum OtaUpgradeTaskStatus {

    PENDING("待执行"),
    IN_PROGRESS("升级中"),
    COMPLETED("完成"),
    FAILED("失败");

    private static final Set<OtaUpgradeTaskStatus> TERMINAL_STATUS = EnumSet.of(COMPLETED, FAILED);

    private final String desc;

    OtaUpgradeTaskStatus(String desc) {
        this.desc = desc;
    }

    public boolean checkTerminal() {
        return TERMINAL_STATUS.contains(this);
    }

    public static Set<OtaUpgradeTaskStatus> terminalStatuses() {
        return Collections.unmodifiableSet(TERMINAL_STATUS);
    }

}
