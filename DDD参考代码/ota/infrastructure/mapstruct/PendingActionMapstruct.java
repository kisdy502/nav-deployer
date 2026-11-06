package com.zioneer.robotmanager.robot.ota.infrastructure.mapstruct;

import com.zioneer.robotmanager.robot.action.domain.Action;
import com.zioneer.robotmanager.robot.action.domain.payload.ActionPayload;
import com.zioneer.robotmanager.robot.action.domain.payload.OtaUpgradePayload;
import com.zioneer.robotmanager.robot.ota.application.dto.PendingActionVO;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageSource;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.OtaSourceAccess;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.OtaSourceAccessResolver;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@Mapper(componentModel = "spring")
public abstract class PendingActionMapstruct {

    @Autowired
    protected OtaSourceAccessResolver otaSourceAccessResolver;

    @Mapping(target = "actionId", expression = "java(toStringId(action.getId()))")
    @Mapping(target = "payload", expression = "java(refreshSourceUrls(action.getPayload()))")
    public abstract PendingActionVO toVO(Action action);

    public String toStringId(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    public ActionPayload refreshSourceUrls(ActionPayload payload) {
        if (!(payload instanceof OtaUpgradePayload upgrade) || upgrade.sources() == null) {
            return payload;
        }
        return refreshPayload(upgrade);
    }

    @Mapping(target = "sources", source = "sources", qualifiedByName = "refreshSourceList")
    protected abstract OtaUpgradePayload refreshPayload(OtaUpgradePayload payload);

    @Named("refreshSourceList")
    protected List<OtaUpgradePayload.Source> refreshSourceList(List<OtaUpgradePayload.Source> sources) {
        return sources.stream().map(this::refreshSource).toList();
    }

    protected OtaUpgradePayload.Source refreshSource(OtaUpgradePayload.Source source) {
        OtaPackageSource packageSource = toOtaPackageSource(source);
        if (packageSource.type() == null) {
            return new OtaUpgradePayload.Source(source.key(), source.type(), source.path(), source.version(),
                    source.downloadUrl(), null, source.expiresAt(), source.sha256(), source.size());
        }
        OtaSourceAccess access = otaSourceAccessResolver.resolve(packageSource);
        return new OtaUpgradePayload.Source(source.key(), source.type(), source.path(), source.version(),
                access.downloadUrl(), access.repository(), access.expiresAt(), source.sha256(), source.size());
    }

    protected abstract OtaPackageSource toOtaPackageSource(OtaUpgradePayload.Source source);
}
