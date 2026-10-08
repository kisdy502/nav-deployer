package com.agv.navdeployer.rms.config;

import com.agv.navdeployer.mapper.NavMapMapper;
import com.agv.navdeployer.rms.gateway.RmsCommandGateway;
import com.agv.navdeployer.rms.gateway.RmsReportGateway;
import com.agv.navdeployer.rms.gateway.RmsServiceGateway;
import com.agv.navdeployer.rms.map.RmsMapService;
import com.agv.navdeployer.rms.map.RmsMapState;
import com.agv.navdeployer.rms.protocol.keys.RmsCommandKeys;
import com.agv.navdeployer.rms.protocol.keys.RmsReportKeys;
import com.agv.navdeployer.rms.protocol.keys.RmsServiceKeys;
import com.agv.navdeployer.rms.session.RmsRobotSession;
import com.agv.navdeployer.rms.state.RobotStateView;
import com.agv.navdeployer.rms.task.RmsResultReporter;
import com.agv.navdeployer.rms.task.RmsTaskService;
import com.agv.navdeployer.service.MapModeTaskService;
import com.agv.navdeployer.service.NavMapService;
import com.agv.navdeployer.sim.RosCommandDispatcher;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RMS 对接装配：rms.enabled=false 时只建 Bean（registry/reporter 供内部查询），
 * 不建立 zenoh 会话。启用后由 {@link RmsRobotSession#ensureSession()} 常态维持。
 *
 * <p>三个网关按通信方向各管一摊：A=robot 发布（Report）、B=robot 查询（Service）、
 * C=RMS 查询应答（Command），Session 只做生命周期编排。
 */
@Configuration
@EnableConfigurationProperties(RmsProperties.class)
public class RmsConfig {

    private static final Logger log = LoggerFactory.getLogger(RmsConfig.class);

    @Bean
    public RmsReportKeys rmsReportKeys(RmsProperties props) {
        RmsProperties.Robot robot = props.getRobot();
        return new RmsReportKeys(props.getZenoh().getRobotKeyPrefix(),
                robot.getRobotType(), robot.getRobotCode());
    }

    @Bean
    public RmsServiceKeys rmsServiceKeys(RmsProperties props) {
        RmsProperties.Robot robot = props.getRobot();
        return new RmsServiceKeys(props.getZenoh().getRobotKeyPrefix(),
                robot.getRobotType(), robot.getRobotCode(), props.getZenoh().getRegisterKey());
    }

    @Bean
    public RmsCommandKeys rmsCommandKeys(RmsProperties props) {
        RmsProperties.Robot robot = props.getRobot();
        return new RmsCommandKeys(props.getZenoh().getRobotKeyPrefix(),
                robot.getRobotType(), robot.getRobotCode());
    }

    @Bean
    public RmsReportGateway rmsReportGateway(RmsReportKeys keys, RobotStateView stateView,
                                             ObjectMapper mapper, RmsProperties props) {
        return new RmsReportGateway(keys, stateView, mapper, props.getReport());
    }

    @Bean
    public RmsServiceGateway rmsServiceGateway(RmsServiceKeys keys, ObjectMapper mapper) {
        return new RmsServiceGateway(keys, mapper);
    }

    @Bean
    public RmsCommandGateway rmsCommandGateway(RmsMapService mapService, RmsTaskService taskService,
                                               ObjectMapper mapper, RmsCommandKeys keys) {
        return new RmsCommandGateway(taskService, mapService, mapper, keys);
    }

    @Bean
    public RmsMapService rmsMapService(MapModeTaskService mapTaskService,
                                       NavMapService navMapService,
                                       NavMapMapper navMapMapper,
                                       RosCommandDispatcher dispatcher,
                                       SimAgvTelemetry telemetry,
                                       RmsMapState mapState) {
        return new RmsMapService(mapTaskService, navMapService, navMapMapper,
                dispatcher, telemetry, mapState);
    }

    @Bean(destroyMethod = "shutdown")
    public RmsRobotSession rmsRobotSession(RmsProperties props,
                                           RobotStateView stateView,
                                           RmsReportGateway reportGateway,
                                           RmsServiceGateway serviceGateway,
                                           RmsCommandGateway commandGateway,
                                           RmsResultReporter resultReporter,
                                           ObjectMapper mapper) {
        return new RmsRobotSession(props, stateView, reportGateway, serviceGateway,
                commandGateway, resultReporter, mapper);
    }

    @Bean
    public ApplicationRunner rmsSessionBootstrap(RmsProperties props, RmsRobotSession session) {
        return args -> {
            if (!props.isEnabled()) {
                log.info("rms integration disabled, zenoh session stays idle");
                return;
            }
            session.start();
        };
    }
}
