package com.zioneer.robotmanager.robot.ota.infrastructure.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.zioneer.robotmanager.robot.ota.application.query.OtaPackageQuery;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OtaPackageMapper extends BaseMapper<OtaPackage> {

    IPage<OtaPackage> pageByQuery(IPage<OtaPackage> page, @Param("query") OtaPackageQuery query);
}
