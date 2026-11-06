package com.zioneer.robotmanager.robot.ota.domain.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.zioneer.robotmanager.robot.ota.application.query.OtaReleaseQuery;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaRelease;

public interface OtaReleaseRepository {

    void insert(OtaRelease otaRelease);

    OtaRelease findById(Long id);

    IPage<OtaRelease> page(OtaReleaseQuery query);
}
