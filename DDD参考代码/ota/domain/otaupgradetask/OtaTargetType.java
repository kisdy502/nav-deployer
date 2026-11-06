package com.zioneer.robotmanager.robot.ota.domain.otaupgradetask;

import lombok.Getter;

@Getter
public enum OtaTargetType {

    ROBOT("机器人");

    private final String desc;

    OtaTargetType(String desc) {
        this.desc = desc;
    }

}
