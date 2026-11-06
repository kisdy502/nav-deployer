package com.zioneer.robotmanager.robot.ota.domain.repository;

import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaTargetType;
import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaUpgradeTask;
import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaUpgradeTaskStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface OtaUpgradeTaskRepository {

    void insertBatch(List<OtaUpgradeTask> tasks);

    void update(OtaUpgradeTask task);

    void updateBatch(List<OtaUpgradeTask> tasks);

    /**
     * 只在库中该任务仍未终结时写入。返回是否真的写成功——false 表示已被另一条路径先改掉了，本次作废。
     */
    boolean updateIfUnfinished(OtaUpgradeTask task);

    OtaUpgradeTask findById(Long id);

    /**
     * 按发布分组统计各状态的升级任务数量，key 为发布 ID。
     */
    Map<Long, Map<OtaUpgradeTaskStatus, Long>> countByReleaseIds(List<Long> releaseIds);

    List<OtaUpgradeTask> findByReleaseId(Long releaseId);

    /**
     * 查询目标下每种安装包类型中最新创建的一条升级任务。
     */
    List<OtaUpgradeTask> findLatestPerKindByTargetCode(OtaTargetType targetType, String targetCode);

    /**
     * 所有未终结的升级任务。
     */
    List<OtaUpgradeTask> findUnfinished();

    /**
     * 目标下所有未终结的升级任务。
     */
    List<OtaUpgradeTask> findUnfinishedByTargetId(String targetId);
}
