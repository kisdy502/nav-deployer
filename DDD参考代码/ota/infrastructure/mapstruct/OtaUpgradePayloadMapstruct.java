package com.zioneer.robotmanager.robot.ota.infrastructure.mapstruct;

import com.zioneer.robotmanager.robot.action.domain.payload.OtaUpgradePayload;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageSnapshot;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageSource;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.OtaSourceAccess;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.OtaSourceAccessResolver;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public abstract class OtaUpgradePayloadMapstruct {

    @Autowired
    protected OtaSourceAccessResolver otaSourceAccessResolver;

    public abstract OtaUpgradePayload toUpgradePayload(OtaPackageSnapshot snapshot);
    protected OtaUpgradePayload.Source toSource(OtaPackageSource source) {
        OtaSourceAccess access = otaSourceAccessResolver.resolve(source);
        return new OtaUpgradePayload.Source(
                source.key(),
                source.type() == null ? null : source.type().name(),
                source.path(),
                source.version(),
                access.downloadUrl(),
                access.repository(),
                access.expiresAt(),
                source.sha256(),
                source.size());
    }
}
