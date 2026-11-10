package com.agv.navdeployer.task.infrastructure.repository.mapper;

import com.agv.navdeployer.task.domain.movetask.MoveTaskSegment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MoveTaskSegmentMapper extends BaseMapper<MoveTaskSegment> {
}
