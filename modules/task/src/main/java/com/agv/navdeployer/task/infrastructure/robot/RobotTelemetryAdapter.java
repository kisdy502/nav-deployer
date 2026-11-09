package com.agv.navdeployer.task.infrastructure.robot;

import com.agv.navdeployer.robot.domain.ArmStateMonitor;
import com.agv.navdeployer.robot.domain.SimAgvProperties;
import com.agv.navdeployer.robot.domain.SimAgvTelemetry;
import com.agv.navdeployer.task.domain.port.RobotPose;
import com.agv.navdeployer.task.domain.port.RobotStatus;
import com.agv.navdeployer.task.domain.port.RobotTelemetryPort;
import org.springframework.stereotype.Component;

@Component
public class RobotTelemetryAdapter implements RobotTelemetryPort {

    private final SimAgvTelemetry telemetry;
    private final ArmStateMonitor armStateMonitor;
    private final SimAgvProperties props;

    public RobotTelemetryAdapter(SimAgvTelemetry telemetry,
                                 ArmStateMonitor armStateMonitor,
                                 SimAgvProperties props) {
        this.telemetry = telemetry;
        this.armStateMonitor = armStateMonitor;
        this.props = props;
    }

    @Override
    public RobotStatus status() {
        SimAgvTelemetry.StatusSnapshot snapshot = telemetry.getStatus();
        if (snapshot == null) {
            return null;
        }
        return new RobotStatus(snapshot.mode(), snapshot.mapName(), snapshot.poseInitialized(),
                snapshot.battery(), snapshot.charging());
    }

    @Override
    public RobotPose mapPose() {
        SimAgvTelemetry.PoseSnapshot pose = telemetry.getMapPose();
        return pose == null ? null : new RobotPose(pose.x(), pose.y(), pose.yaw());
    }

    @Override
    public boolean statusFresh() {
        return telemetry.isStatusFresh(props.getStatusFreshMs());
    }

    @Override
    public long statusAgeMs() {
        return telemetry.statusAgeMs();
    }

    @Override
    public double odomLinearX() {
        return telemetry.getOdomLinearX();
    }

    @Override
    public double odomLinearY() {
        return telemetry.getOdomLinearY();
    }

    @Override
    public double odomAngularZ() {
        return telemetry.getOdomAngularZ();
    }

    @Override
    public long odomCount() {
        return telemetry.getOdomCount();
    }

    @Override
    public boolean armMovementAllowed() {
        return armStateMonitor == null || armStateMonitor.movementAllowed();
    }

    @Override
    public String armState() {
        return armStateMonitor == null ? null : armStateMonitor.state().name();
    }
}
