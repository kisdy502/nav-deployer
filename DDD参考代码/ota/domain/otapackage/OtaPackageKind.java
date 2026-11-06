package com.zioneer.robotmanager.robot.ota.domain.otapackage;

import lombok.Getter;

@Getter
public enum OtaPackageKind {

    ROBOT("机器人"),
    SERVICE("服务"),
    FIRMWARE("固件"),
    CONFIGURATION("配置");

    private final String desc;

    OtaPackageKind(String desc) {
        this.desc = desc;
    }

}
