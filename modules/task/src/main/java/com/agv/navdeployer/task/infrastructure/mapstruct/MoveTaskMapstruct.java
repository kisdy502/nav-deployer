package com.agv.navdeployer.task.infrastructure.mapstruct;

import com.agv.navdeployer.task.application.dto.MoveTaskDTO;
import com.agv.navdeployer.task.domain.movetask.MoveTask;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface MoveTaskMapstruct {

    MoveTaskDTO toDTO(MoveTask task);

    List<MoveTaskDTO> toDTOList(List<MoveTask> tasks);
}
