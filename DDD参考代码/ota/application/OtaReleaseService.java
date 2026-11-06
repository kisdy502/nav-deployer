package com.zioneer.robotmanager.robot.ota.application;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.zioneer.robotmanager.common.core.result.PageResult;
import com.zioneer.robotmanager.common.mybatis.support.SnowflakeIdGenerator;
import com.zioneer.robotmanager.robot.domain.entity.RobotEntity;
import com.zioneer.robotmanager.robot.ota.application.cmd.CreateOtaReleaseCmd;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaReleaseDetailVO;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaReleaseVO;
import com.zioneer.robotmanager.robot.ota.application.query.OtaReleaseQuery;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageSnapshot;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaRelease;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaReleaseDomainService;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaReleaseProgress;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaTarget;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaTargetDimension;
import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaUpgradeTaskStatus;
import com.zioneer.robotmanager.robot.ota.domain.repository.OtaPackageRepository;
import com.zioneer.robotmanager.robot.ota.domain.repository.OtaReleaseRepository;
import com.zioneer.robotmanager.robot.ota.domain.repository.OtaUpgradeTaskRepository;
import com.zioneer.robotmanager.robot.ota.infrastructure.mapstruct.OtaReleaseMapstruct;
import com.zioneer.robotmanager.robot.mapper.RobotMapper;
import com.zioneer.robotmanager.robot.util.SnowflakeIdParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OtaReleaseService {

    private static final Logger log = LoggerFactory.getLogger(OtaReleaseService.class);

    private final OtaPackageRepository otaPackageRepository;
    private final OtaReleaseRepository otaReleaseRepository;
    private final OtaUpgradeTaskRepository otaUpgradeTaskRepository;
    private final OtaReleaseDomainService otaReleaseDomainService;
    private final OtaUpgradeTaskService otaUpgradeTaskService;
    private final OtaReleaseMapstruct otaReleaseMapstruct;
    private final RobotMapper robotMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Autowired
    public OtaReleaseService(OtaPackageRepository otaPackageRepository,
                                 OtaReleaseRepository otaReleaseRepository,
                                 OtaUpgradeTaskRepository otaUpgradeTaskRepository,
                                 OtaReleaseDomainService otaReleaseDomainService,
                                 OtaUpgradeTaskService otaUpgradeTaskService,
                                 OtaReleaseMapstruct otaReleaseMapstruct,
                                 RobotMapper robotMapper,
                                 SnowflakeIdGenerator snowflakeIdGenerator) {
        this.otaPackageRepository = otaPackageRepository;
        this.otaReleaseRepository = otaReleaseRepository;
        this.otaUpgradeTaskRepository = otaUpgradeTaskRepository;
        this.otaReleaseDomainService = otaReleaseDomainService;
        this.otaUpgradeTaskService = otaUpgradeTaskService;
        this.otaReleaseMapstruct = otaReleaseMapstruct;
        this.robotMapper = robotMapper;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    @Transactional(rollbackFor = Exception.class)
    public String create(CreateOtaReleaseCmd cmd) {
        OtaPackageSnapshot snapshot = OtaPackageSnapshot.of(otaPackageRepository.findById(cmd.getPackageId()));
        OtaRelease otaRelease = new OtaRelease(cmd, snapshot, snowflakeIdGenerator);
        otaReleaseRepository.insert(otaRelease);
        List<OtaTarget> targets = otaReleaseDomainService.expandTargets(
                cmd.getTargetDimension(), cmd.getTargetValues());
        otaUpgradeTaskService.createForRelease(otaRelease, targets);
        log.info("Created ota release {} for {} targets", otaRelease.getId(), targets.size());
        return String.valueOf(otaRelease.getId());
    }

    public PageResult<OtaReleaseVO> page(OtaReleaseQuery query) {
        query.normalizeOrderBy();
        IPage<OtaRelease> page = otaReleaseRepository.page(query);
        List<OtaRelease> records = page.getRecords();
        Map<Long, Map<OtaUpgradeTaskStatus, Long>> countsByRelease = otaUpgradeTaskRepository.countByReleaseIds(
                records.stream().map(OtaRelease::getId).toList());
        Map<String, String> robotNames = loadRobotNames(records);
        List<OtaReleaseVO> list = records.stream()
                .map(release -> otaReleaseMapstruct.toReleaseVO(release, OtaReleaseProgress.of(
                        countsByRelease.getOrDefault(release.getId(), Map.of())), robotNames))
                .toList();
        return new PageResult<>(list, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public OtaReleaseDetailVO detail(Long id) {
        OtaRelease otaRelease = otaReleaseRepository.findById(id);
        return otaReleaseMapstruct.toReleaseDetailVO(
                otaRelease, OtaReleaseProgress.of(countsOf(id)), loadRobotNames(List.of(otaRelease)));
    }

    private Map<String, String> loadRobotNames(List<OtaRelease> releases) {
        List<Long> robotIds = releases.stream()
                .filter(release -> release.getTargetDimension() == OtaTargetDimension.ROBOT)
                .flatMap(release -> release.getTargetValues().stream())
                .distinct()
                .map(SnowflakeIdParser::parse)
                .toList();
        if (robotIds.isEmpty()) {
            return Map.of();
        }
        return robotMapper.selectBatchIds(robotIds)
                .stream()
                .collect(Collectors.toMap(
                        robot -> String.valueOf(robot.getId()), RobotEntity::getRobotName, (first, ignored) -> first));
    }

    private Map<OtaUpgradeTaskStatus, Long> countsOf(Long releaseId) {
        return otaUpgradeTaskRepository.countByReleaseIds(List.of(releaseId))
                .getOrDefault(releaseId, Map.of());
    }
}
