package com.agv.navdeployer.task.domain.repository;

import com.agv.navdeployer.task.domain.movetask.MoveTask;

import java.util.List;

public interface MoveTaskRepository {

    void insert(MoveTask task);

    void update(MoveTask task);

    MoveTask findById(Long id);

    MoveTask findLatestActive();

    List<MoveTask> findList(String status, int limit);

    /**
     * 服务重启恢复：把非终态任务批量标记为 FAILED
     *
     * @return 恢复条数
     */
    int markStaleInterrupted(String errorMessage);
}
