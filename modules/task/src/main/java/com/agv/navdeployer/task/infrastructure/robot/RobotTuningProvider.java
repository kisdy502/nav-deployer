package com.agv.navdeployer.task.infrastructure.robot;

import com.agv.navdeployer.robot.domain.SimAgvProperties;
import com.agv.navdeployer.task.domain.port.RobotTuning;
import org.springframework.stereotype.Component;

@Component
public class RobotTuningProvider {

    private final SimAgvProperties props;

    public RobotTuningProvider(SimAgvProperties props) {
        this.props = props;
    }

    public RobotTuning tuning() {
        return new RobotTuning(
                props.getStatusFreshMs(),
                props.getModeTaskTimeoutMs(),
                props.getLocalizationTaskTimeoutMs(),
                props.getGoalTimeoutSeconds(),
                props.getDefaultMaxSpeed(),
                props.getDefaultStep(),
                props.getFollowPathStartRadius());
    }
}
