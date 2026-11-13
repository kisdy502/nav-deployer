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
