package com.zioneer.robotmanager.robot.ota.domain.otaupgradetask;

import lombok.Getter;

@Getter
public enum OtaCompletionSource {

    ACTION("升级Action上报完成"),
    MANIFEST("清单比对满足");

    private final String desc;

    OtaCompletionSource(String desc) {
        this.desc = desc;
    }

}
