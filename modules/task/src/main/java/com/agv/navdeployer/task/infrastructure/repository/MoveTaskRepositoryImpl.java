package com.agv.navdeployer.task.infrastructure.repository;

import com.agv.navdeployer.common.error.BusinessException;
import com.agv.navdeployer.task.domain.movetask.MoveTask;
import com.agv.navdeployer.task.domain.movetask.TaskErrorCode;
import com.agv.navdeployer.task.domain.repository.MoveTaskRepository;
import com.agv.navdeployer.task.infrastructure.repository.mapper.MoveTaskMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class MoveTaskRepositoryImpl implements MoveTaskRepository {

    private final MoveTaskMapper moveTaskMapper;

    public MoveTaskRepositoryImpl(MoveTaskMapper moveTaskMapper) {
        this.moveTaskMapper = moveTaskMapper;
    }

    @Override
    public void insert(MoveTask task) {
        moveTaskMapper.insert(task);
    }

    @Override
    public void update(MoveTask task) {
        moveTaskMapper.updateById(task);
    }

    @Override
    public void updateFeedback(MoveTask task, boolean markExecuting) {
        // 条件更新：任务一旦终态（或已暂停收口）就不再刷新——迟到的 feedback 事务无法覆盖 FAILED/SUCCEEDED
        LambdaUpdateWrapper<MoveTask> wrapper = Wrappers.lambdaUpdate(MoveTask.class)
                .eq(MoveTask::getId, task.getId())
                .in(MoveTask::getStatus, MoveTask.STATUS_CREATED, MoveTask.STATUS_DISPATCHED,
                        MoveTask.STATUS_EXECUTING)
                .set(MoveTask::getCurrentX, task.getCurrentX())
                .set(MoveTask::getCurrentY, task.getCurrentY())
                .set(MoveTask::getCurrentTheta, task.getCurrentTheta())
                .set(MoveTask::getAgvState, task.getAgvState())
                .set(MoveTask::getUpdatedAt, LocalDateTime.now());
        if (markExecuting) {
            wrapper.set(MoveTask::getStatus, MoveTask.STATUS_EXECUTING);
        }
        moveTaskMapper.update(null, wrapper);
    }

    @Override
    public List<MoveTask> findNonTerminal() {
        return moveTaskMapper.selectList(Wrappers.lambdaQuery(MoveTask.class)
                .in(MoveTask::getStatus, MoveTask.STATUS_CREATED, MoveTask.STATUS_DISPATCHED,
                        MoveTask.STATUS_EXECUTING, MoveTask.STATUS_PAUSED));
    }

    @Override
    public void updateDispatch(MoveTask task) {
        moveTaskMapper.update(null, Wrappers.lambdaUpdate(MoveTask.class)
                .eq(MoveTask::getId, task.getId())
                .in(MoveTask::getStatus, MoveTask.STATUS_CREATED, MoveTask.STATUS_DISPATCHED,
                        MoveTask.STATUS_EXECUTING)
                .set(MoveTask::getGoalId, task.getGoalId())
                .set(MoveTask::getSegmentSeq, task.getSegmentSeq())
                .set(MoveTask::getStatus, task.getStatus())
                .set(MoveTask::getUpdatedAt, LocalDateTime.now()));
    }

    @Override
    public MoveTask findById(Long id) {
        MoveTask task = moveTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(TaskErrorCode.MOVE_TASK_NOT_FOUND, "任务 " + id + " 不存在");
        }
        return task;
    }

    @Override
    public MoveTask findLatestActive() {
        return moveTaskMapper.selectOne(Wrappers.lambdaQuery(MoveTask.class)
                .in(MoveTask::getStatus, MoveTask.STATUS_CREATED, MoveTask.STATUS_DISPATCHED,
                        MoveTask.STATUS_EXECUTING, MoveTask.STATUS_PAUSED)
                .orderByDesc(MoveTask::getId)
                .last("LIMIT 1"));
    }

    @Override
    public List<MoveTask> findList(String status, int limit) {
        return moveTaskMapper.selectList(Wrappers.lambdaQuery(MoveTask.class)
                .eq(StringUtils.hasText(status), MoveTask::getStatus, status)
                .orderByDesc(MoveTask::getId)
                .last("LIMIT " + limit));
    }

    @Override
    public int markStaleInterrupted(String errorMessage) {
        LambdaUpdateWrapper<MoveTask> wrapper = Wrappers.lambdaUpdate(MoveTask.class)
                .in(MoveTask::getStatus, MoveTask.STATUS_CREATED, MoveTask.STATUS_DISPATCHED,
                        MoveTask.STATUS_EXECUTING, MoveTask.STATUS_PAUSED)
                .set(MoveTask::getStatus, MoveTask.STATUS_FAILED)
                .set(MoveTask::getErrorMessage, errorMessage)
                .set(MoveTask::getFinishedAt, LocalDateTime.now());
        return moveTaskMapper.update(null, wrapper);
    }
}
