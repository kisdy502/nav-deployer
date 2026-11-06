package com.zioneer.robotmanager.robot.ota.infrastructure.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaTargetType;
import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaUpgradeTask;
import com.zioneer.robotmanager.robot.ota.infrastructure.repository.OtaUpgradeTaskStatusCount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OtaUpgradeTaskMapper extends BaseMapper<OtaUpgradeTask> {

    List<OtaUpgradeTaskStatusCount> countByReleaseIds(@Param("releaseIds") List<Long> releaseIds);

    List<OtaUpgradeTask> selectLatestPerKind(@Param("targetType") OtaTargetType targetType,
                                             @Param("targetCode") String targetCode);
}
