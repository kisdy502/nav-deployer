package com.zioneer.robotmanager.robot.ota.infrastructure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zioneer.robotmanager.common.core.error.BusinessException;
import com.zioneer.robotmanager.robot.error.RobotManagementErrorCode;
import com.zioneer.robotmanager.robot.ota.application.query.OtaPackageQuery;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackage;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageKind;
import com.zioneer.robotmanager.robot.ota.domain.repository.OtaPackageRepository;
import com.zioneer.robotmanager.robot.ota.infrastructure.repository.mapper.OtaPackageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class OtaPackageRepositoryImpl implements OtaPackageRepository {

    private final OtaPackageMapper otaPackageMapper;

    @Autowired
    public OtaPackageRepositoryImpl(OtaPackageMapper otaPackageMapper) {
        this.otaPackageMapper = otaPackageMapper;
    }

    @Override
    public void insert(OtaPackage otaPackage) {
        otaPackageMapper.insert(otaPackage);
    }

    @Override
    public void update(OtaPackage otaPackage) {
        otaPackageMapper.updateById(otaPackage);
    }

    @Override
    public void delete(OtaPackage otaPackage) {
        otaPackageMapper.deleteById(otaPackage.getId());
    }

    @Override
    public OtaPackage findById(Long id) {
        return Optional.ofNullable(otaPackageMapper.selectById(id))
                .orElseThrow(() -> new BusinessException(RobotManagementErrorCode.OTA_PACKAGE_NOT_FOUND));
    }

    @Override
    public IPage<OtaPackage> page(OtaPackageQuery query) {
        return otaPackageMapper.pageByQuery(new Page<>(query.getPageNum(), query.getPageSize()), query);
    }

    @Override
    public List<OtaPackage> findByKindNameVersion(OtaPackageKind kind, String name, String version) {
        return otaPackageMapper.selectList(new LambdaQueryWrapper<OtaPackage>()
                .eq(OtaPackage::getKind, kind)
                .eq(OtaPackage::getName, name)
                .eq(OtaPackage::getVersion, version));
    }
}
