package com.agv.navdeployer.task.infrastructure.robot;

import com.agv.navdeployer.robot.domain.LocalizationOperation;
import com.agv.navdeployer.robot.infrastructure.LocalizationOperationTracker;
import com.agv.navdeployer.task.domain.port.OperationState;
import com.agv.navdeployer.task.domain.port.RobotOperationPort;
import org.springframework.stereotype.Component;

@Component
public class RobotOperationAdapter implements RobotOperationPort {

    private final LocalizationOperationTracker tracker;

    public RobotOperationAdapter(LocalizationOperationTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public OperationState query(String operationId) {
        LocalizationOperation operation = tracker.get(operationId);
        if (operation == null) {
            return null;
        }
        return new OperationState(
                operation.operationId(),
                operation.isTerminal(),
                LocalizationOperation.STATE_SUCCEEDED.equals(operation.state()),
                operation.stage(),
                operation.message(),
                operation.failureReason());
    }

    @Override
    public void refresh(String operationId) {
        tracker.refreshFromRobot(operationId);
    }
}
