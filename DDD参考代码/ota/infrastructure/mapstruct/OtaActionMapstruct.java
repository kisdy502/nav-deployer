package com.zioneer.robotmanager.robot.ota.infrastructure.mapstruct;

import com.zioneer.robotmanager.robot.action.domain.Action;
import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaAction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OtaActionMapstruct {

    @Mapping(target = "actionId", source = "id")
    OtaAction toOtaAction(Action action);
}
