package com.zioneer.robotmanager.robot.ota.domain.otarelease;

import lombok.Getter;

@Getter
public enum OtaReleaseStatus {

    PENDING("待执行"),
    IN_PROGRESS("升级中"),
    COMPLETED("已完成");

    private final String desc;

    OtaReleaseStatus(String desc) {
        this.desc = desc;
    }

}
