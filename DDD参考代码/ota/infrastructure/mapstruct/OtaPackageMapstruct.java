package com.zioneer.robotmanager.robot.ota.infrastructure.mapstruct;

import com.zioneer.robotmanager.robot.ota.application.dto.OtaPackageVO;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackage;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface OtaPackageMapstruct {

    @Mapping(target = "id", expression = "java(toStringId(otaPackage.getId()))")
    @Mapping(target = "totalSize", expression = "java(otaPackage.calculateTotalSize())")
    OtaPackageVO toVO(OtaPackage otaPackage);

    default String toStringId(Long value) {
        return value == null ? null : String.valueOf(value);
    }
}
