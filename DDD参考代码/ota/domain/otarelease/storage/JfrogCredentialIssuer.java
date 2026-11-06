package com.zioneer.robotmanager.robot.ota.domain.otarelease.storage;

import com.zioneer.robotmanager.robot.ota.domain.config.OtaStorageProperties;

public interface JfrogCredentialIssuer {

    JfrogCredential issue(OtaStorageProperties.Jfrog jfrog);
}
