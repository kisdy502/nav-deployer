package com.zioneer.robotmanager.robot.ota.application.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.zioneer.robotmanager.robot.action.domain.ActionStatus;
import com.zioneer.robotmanager.robot.action.domain.ActionType;
import com.zioneer.robotmanager.robot.action.domain.payload.ActionPayload;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PendingActionVO {

    private String actionId;
    private ActionType actionType;
    private ActionStatus actionStatus;

    @JsonUnwrapped
    private ActionPayload payload;
}
