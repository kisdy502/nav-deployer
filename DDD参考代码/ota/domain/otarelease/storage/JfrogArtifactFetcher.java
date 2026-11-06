package com.zioneer.robotmanager.robot.ota.domain.otarelease.storage;

import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageSource;

public interface JfrogArtifactFetcher {

    /**
     * 取回制品内容流；rangeHeader 原样透传给上游（200 / 206 由上游决定）。
     */
    JfrogArtifactFetch fetch(OtaPackageSource source, String rangeHeader);
}
