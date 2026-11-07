package com.agv.navdeployer.task.infrastructure.robot;

import com.agv.navdeployer.robot.infrastructure.RosbridgeClient;
import com.agv.navdeployer.task.domain.port.RobotConnectionPort;
import org.springframework.stereotype.Component;

@Component
public class RobotConnectionAdapter implements RobotConnectionPort {

    private final RosbridgeClient rosbridgeClient;

    public RobotConnectionAdapter(RosbridgeClient rosbridgeClient) {
        this.rosbridgeClient = rosbridgeClient;
    }

    @Override
    public boolean isConnected() {
        return rosbridgeClient.isConnected();
    }
}
