package com.zioneer.robotmanager.robot.ota.infrastructure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zioneer.robotmanager.common.core.error.BusinessException;
import com.zioneer.robotmanager.robot.error.RobotManagementErrorCode;
import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaTargetType;
import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaUpgradeTask;
import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaUpgradeTaskStatus;
import com.zioneer.robotmanager.robot.ota.domain.repository.OtaUpgradeTaskRepository;
import com.zioneer.robotmanager.robot.ota.infrastructure.repository.mapper.OtaUpgradeTaskMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class OtaUpgradeTaskRepositoryImpl implements OtaUpgradeTaskRepository {

    private final OtaUpgradeTaskMapper otaUpgradeTaskMapper;

    @Autowired
    public OtaUpgradeTaskRepositoryImpl(OtaUpgradeTaskMapper otaUpgradeTaskMapper) {
        this.otaUpgradeTaskMapper = otaUpgradeTaskMapper;
    }

    @Override
    public void insertBatch(List<OtaUpgradeTask> tasks) {
        otaUpgradeTaskMapper.insert(tasks);
    }

    @Override
    public void update(OtaUpgradeTask task) {
        otaUpgradeTaskMapper.updateById(task);
    }

    @Override
    public OtaUpgradeTask findById(Long id) {
        return Optional.ofNullable(otaUpgradeTaskMapper.selectById(id))
                .orElseThrow(() -> new BusinessException(RobotManagementErrorCode.OTA_UPGRADE_TASK_NOT_FOUND));
    }

    @Override
    public Map<Long, Map<OtaUpgradeTaskStatus, Long>> countByReleaseIds(List<Long> releaseIds) {
        if (releaseIds == null || releaseIds.isEmpty()) {
            return Map.of();
        }
        return otaUpgradeTaskMapper.countByReleaseIds(releaseIds).stream()
                .collect(Collectors.groupingBy(
                        OtaUpgradeTaskStatusCount::releaseId,
                        Collectors.toMap(OtaUpgradeTaskStatusCount::status, OtaUpgradeTaskStatusCount::total)));
    }

    @Override
    public List<OtaUpgradeTask> findByReleaseId(Long releaseId) {
        return otaUpgradeTaskMapper.selectList(new LambdaQueryWrapper<OtaUpgradeTask>()
                .eq(OtaUpgradeTask::getReleaseId, releaseId)
                .orderByAsc(OtaUpgradeTask::getCreatedAt));
    }

    @Override
    public List<OtaUpgradeTask> findLatestPerKindByTargetCode(OtaTargetType targetType, String targetCode) {
        return otaUpgradeTaskMapper.selectLatestPerKind(targetType, targetCode);
    }

    @Override
    public void updateBatch(List<OtaUpgradeTask> tasks) {
        if (CollectionUtils.isEmpty(tasks)) return;
        tasks.forEach(this::updateIfUnfinished);
    }

    @Override
    public List<OtaUpgradeTask> findUnfinished() {
        return otaUpgradeTaskMapper.selectList(new LambdaQueryWrapper<OtaUpgradeTask>()
                .notIn(OtaUpgradeTask::getStatus, OtaUpgradeTaskStatus.terminalStatuses()));
    }

    @Override
    public boolean updateIfUnfinished(OtaUpgradeTask task) {
        return otaUpgradeTaskMapper.update(task, new LambdaUpdateWrapper<OtaUpgradeTask>()
                .eq(OtaUpgradeTask::getId, task.getId())
                .notIn(OtaUpgradeTask::getStatus, OtaUpgradeTaskStatus.terminalStatuses())) > 0;
    }

    @Override
    public List<OtaUpgradeTask> findUnfinishedByTargetId(String targetId) {
        return otaUpgradeTaskMapper.selectList(new LambdaQueryWrapper<OtaUpgradeTask>()
                .eq(OtaUpgradeTask::getTargetId, targetId)
                .notIn(OtaUpgradeTask::getStatus, OtaUpgradeTaskStatus.terminalStatuses()));
    }
}
