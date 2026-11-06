package com.zioneer.robotmanager.robot.ota.infrastructure.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.zioneer.robotmanager.robot.ota.application.query.OtaReleaseQuery;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaRelease;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OtaReleaseMapper extends BaseMapper<OtaRelease> {

    IPage<OtaRelease> pageByQuery(IPage<OtaRelease> page, @Param("query") OtaReleaseQuery query);
}
