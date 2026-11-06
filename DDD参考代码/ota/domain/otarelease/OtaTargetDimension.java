package com.zioneer.robotmanager.robot.ota.domain.otarelease;

import lombok.Getter;

@Getter
public enum OtaTargetDimension {

    ROBOT_TYPE("按机器人型号"),
    ROBOT("按机器人");

    private final String desc;

    OtaTargetDimension(String desc) {
        this.desc = desc;
    }

}
