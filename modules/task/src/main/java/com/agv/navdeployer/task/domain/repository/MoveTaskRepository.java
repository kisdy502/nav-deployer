package com.agv.navdeployer.task.domain.repository;

import com.agv.navdeployer.task.domain.movetask.MoveTask;

import java.util.List;

public interface MoveTaskRepository {

    void insert(MoveTask task);

    void update(MoveTask task);

    /** feedback 周期刷新：只更新位姿/状态字段，且仅当任务仍处于非终态（防迟到事务覆盖终态判定） */
    void updateFeedback(MoveTask task, boolean markExecuting);

    List<MoveTask> findNonTerminal();

    MoveTask findById(Long id);

    MoveTask findLatestActive();

    List<MoveTask> findList(String status, int limit);

    /**
     * 将库中指定非终态批量改为 FAILED
     *
     * @return 恢复数
     */
    int markStaleInterrupted(String errorMessage);
}
