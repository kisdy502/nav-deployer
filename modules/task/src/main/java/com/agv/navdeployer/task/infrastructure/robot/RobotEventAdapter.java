package com.agv.navdeployer.task.infrastructure.robot;

import com.agv.navdeployer.robot.interfaces.SimAgvSsePublisher;
import com.agv.navdeployer.task.domain.port.RobotEventPort;
import org.springframework.stereotype.Component;

@Component
public class RobotEventAdapter implements RobotEventPort {

    private final SimAgvSsePublisher ssePublisher;

    public RobotEventAdapter(SimAgvSsePublisher ssePublisher) {
        this.ssePublisher = ssePublisher;
    }

    @Override
    public void publish(String event, Object payload) {
        ssePublisher.publishEvent(event, payload);
    }
}
