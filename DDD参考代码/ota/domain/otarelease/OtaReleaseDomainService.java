package com.zioneer.robotmanager.robot.ota.domain.otarelease;

import com.zioneer.robotmanager.common.core.error.BusinessException;
import com.zioneer.robotmanager.robot.domain.entity.RobotEntity;
import com.zioneer.robotmanager.robot.domain.entity.RobotInformationEntity;
import com.zioneer.robotmanager.robot.domain.model.RobotVersionManifestEntry;
import com.zioneer.robotmanager.robot.error.RobotManagementErrorCode;
import com.zioneer.robotmanager.robot.mapper.RobotInformationMapper;
import com.zioneer.robotmanager.robot.mapper.RobotMapper;
import com.zioneer.robotmanager.robot.ota.application.OtaManifestPullService;
import com.zioneer.robotmanager.robot.util.SnowflakeIdParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 发布目标的展开与当前清单装填：把「所选维度 + 所选值」还原成实际要升级的机器人；
 * 清单统一从 robot_information 读取（{@link #attachCurrentManifests}），快照来源只有数据库一处。
 */
@Service
public class OtaReleaseDomainService {

    private final RobotMapper robotMapper;
    private final RobotInformationMapper robotInformationMapper;
    private final OtaManifestPullService otaManifestPullService;

    @Autowired
    public OtaReleaseDomainService(RobotMapper robotMapper, RobotInformationMapper robotInformationMapper,
                                   OtaManifestPullService otaManifestPullService) {
        this.robotMapper = robotMapper;
        this.robotInformationMapper = robotInformationMapper;
        this.otaManifestPullService = otaManifestPullService;
    }

    public List<OtaTarget> expandTargets(OtaTargetDimension dimension, List<String> values) {
        List<RobotEntity> robots = switch (dimension) {
            case ROBOT_TYPE -> robotMapper.listEnabledByTypeNos(values);
            case ROBOT -> robotMapper.listEnabledByIds(values.stream().map(SnowflakeIdParser::parse).toList());
        };
        if (robots.isEmpty()) {
            throw new BusinessException(RobotManagementErrorCode.OTA_RELEASE_TARGET_EMPTY);
        }
        otaManifestPullService.refreshCurrentManifests(
                robots.stream().map(RobotEntity::getRobotCode).toList());
        return attachCurrentManifests(robots);
    }

    public List<OtaTarget> attachCurrentManifests(List<RobotEntity> robots) {
        Map<Long, List<RobotVersionManifestEntry>> manifestsByRobotId =
                robotInformationMapper.selectCurrentManifestsByRobotIds(
                                robots.stream().map(RobotEntity::getId).toList())
                        .stream()
                        .filter(information -> information.getCurrentManifest() != null)
                        .collect(Collectors.toMap(
                                RobotInformationEntity::getRobotId,
                                RobotInformationEntity::getCurrentManifest));
        return robots.stream()
                .map(robot -> new OtaTarget(
                        robot.getId(), robot.getRobotCode(), manifestsByRobotId.get(robot.getId())))
                .toList();
    }
}
