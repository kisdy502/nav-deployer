package com.zioneer.robotmanager.robot.ota.domain.otapackage;

import lombok.Getter;

@Getter
public enum OtaSourceType {

    FILE("单个文件"),
    APT("Debian 仓库中的包"),
    OCI("镜像仓库中的镜像");

    private final String desc;

    OtaSourceType(String desc) {
        this.desc = desc;
    }

    public boolean checkDirectlyDownloadable() {
        return this != OCI;
    }

}
