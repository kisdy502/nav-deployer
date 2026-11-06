package com.zioneer.robotmanager.robot.ota.infrastructure.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zioneer.robotmanager.common.core.error.BusinessException;
import com.zioneer.robotmanager.robot.error.RobotManagementErrorCode;
import com.zioneer.robotmanager.robot.ota.application.query.OtaReleaseQuery;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaRelease;
import com.zioneer.robotmanager.robot.ota.domain.repository.OtaReleaseRepository;
import com.zioneer.robotmanager.robot.ota.infrastructure.repository.mapper.OtaReleaseMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class OtaReleaseRepositoryImpl implements OtaReleaseRepository {

    private final OtaReleaseMapper otaReleaseMapper;

    @Autowired
    public OtaReleaseRepositoryImpl(OtaReleaseMapper otaReleaseMapper) {
        this.otaReleaseMapper = otaReleaseMapper;
    }

    @Override
    public void insert(OtaRelease otaRelease) {
        otaReleaseMapper.insert(otaRelease);
    }

    @Override
    public OtaRelease findById(Long id) {
        return Optional.ofNullable(otaReleaseMapper.selectById(id))
                .orElseThrow(() -> new BusinessException(RobotManagementErrorCode.OTA_RELEASE_NOT_FOUND));
    }

    @Override
    public IPage<OtaRelease> page(OtaReleaseQuery query) {
        return otaReleaseMapper.pageByQuery(new Page<>(query.getPageNum(), query.getPageSize()), query);
    }
}
