package com.zioneer.robotmanager.robot.ota.domain.otarelease.storage;

import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageSource;

public interface OtaSourceAccessStrategy {

    OtaStoragePlatform platform();

    OtaSourceAccess resolve(OtaPackageSource source);
}
