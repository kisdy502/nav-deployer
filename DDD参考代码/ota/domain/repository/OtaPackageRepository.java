package com.zioneer.robotmanager.robot.ota.domain.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.zioneer.robotmanager.robot.ota.application.query.OtaPackageQuery;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackage;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageKind;

import java.util.List;


public interface OtaPackageRepository {

    void insert(OtaPackage otaPackage);

    void update(OtaPackage otaPackage);

    void delete(OtaPackage otaPackage);

    OtaPackage findById(Long id);

    IPage<OtaPackage> page(OtaPackageQuery query);

    List<OtaPackage> findByKindNameVersion(OtaPackageKind kind, String name, String version);
}
