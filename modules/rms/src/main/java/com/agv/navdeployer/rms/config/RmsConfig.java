package com.agv.navdeployer.rms.config;

import com.agv.navdeployer.mapper.NavMapMapper;
import com.agv.navdeployer.rms.gateway.RmsCommandGateway;
import com.agv.navdeployer.rms.gateway.RmsReportGateway;
import com.agv.navdeployer.rms.gateway.RmsServiceGateway;
import com.agv.navdeployer.rms.map.RmsInspectionUploader;
import com.agv.navdeployer.rms.map.RmsMapService;
import com.agv.navdeployer.rms.map.RmsMapState;
import com.agv.navdeployer.rms.map.RmsTelemetryService;
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
    public RmsCommandGateway rmsCommandGateway(RmsMapService mapService,
                                               RmsTelemetryService telemetryService,
                                               RmsInspectionUploader inspectionUploader,
                                               com.agv.navdeployer.rms.log.RmsLogCollector logCollector,
                                               com.agv.navdeployer.rms.teach.RmsTeachCollector teachCollector,
                                               com.agv.navdeployer.exchange.ScheduleMapAdapter scheduleAdapter,
                                               com.agv.navdeployer.service.NavPointService navPointService,
                                               com.agv.navdeployer.service.NavPathService navPathService,
                                               RmsTaskService taskService,
                                               ObjectMapper mapper, RmsCommandKeys keys) {
        return new RmsCommandGateway(taskService, mapService, telemetryService,
                inspectionUploader, logCollector, teachCollector, scheduleAdapter, navPointService, navPathService,
                mapper, keys);
    }

    @Bean
    public com.agv.navdeployer.rms.log.RmsLogCollector rmsLogCollector(ObjectMapper mapper,
                                                                        RmsProperties properties) {
        return new com.agv.navdeployer.rms.log.RmsLogCollector(mapper, properties);
    }

    @Bean
    public com.agv.navdeployer.rms.teach.RmsTeachCollector rmsTeachCollector(ObjectMapper mapper,
                                                                              RmsProperties properties) {
        return new com.agv.navdeployer.rms.teach.RmsTeachCollector(mapper, properties);
    }

    @Bean
    public RmsInspectionUploader rmsInspectionUploader(ObjectMapper mapper,
                                                        RmsProperties properties,
                                                        com.agv.navdeployer.rms.task.RmsTaskRegistry taskRegistry) {
        return new RmsInspectionUploader(mapper, properties, taskRegistry);
    }

    @Bean
    public RmsTelemetryService rmsTelemetryService(SimAgvTelemetry telemetry,
                                                   RmsProperties props, ObjectMapper mapper) {
        return new RmsTelemetryService(telemetry, props, mapper);
    }

    @Bean
    public RmsMapService rmsMapService(MapModeTaskService mapTaskService,
                                       NavMapService navMapService,
                                       NavMapMapper navMapMapper,
                                       RosCommandDispatcher dispatcher,
                                       SimAgvTelemetry telemetry,
                                       RmsMapState mapState,
                                       com.agv.navdeployer.metadata.service.MapMetaService mapMetaService,
                                       com.agv.navdeployer.metadata.exchange.MapPackageService mapPackageService) {
        return new RmsMapService(mapTaskService, navMapService, navMapMapper,
                dispatcher, telemetry, mapState, mapMetaService, mapPackageService);
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
            RmsProperties.Robot robot = props.getRobot();
            if (robot.isRobotSnConfigured()) {
                log.info("rms robot identity: robot_sn={} (from rms.robot.robot_sn / RMS_ROBOT_SN)",
                        robot.getRobotSn());
            } else {
                log.warn("rms robot_sn NOT configured, using SHARED default sn={} — all instances without "
                        + "explicit sn report to the SAME rms robot; set rms.robot.robot_sn (yml) "
                        + "or env RMS_ROBOT_SN to use a dedicated identity", robot.getRobotSn());
            }
            session.start();
        };
    }

    /**
     * 启动对账：上一进程留下的非终态 rms_task 记录已无运行时承载（重启即死），
     * 收口为 failed 防止任务管理页出现永久 RUNNING 的幽灵记录。
     * 对账失败只告警，不阻断启动（历史表是辅助记录）。
     */
    @Bean
    public ApplicationRunner rmsTaskHistoryReconciliation(
            com.agv.navdeployer.mapper.RmsTaskRecordMapper mapper) {
        return args -> {
            try {
                var pending = mapper.selectList(new com.baomidou.mybatisplus.core.conditions.query
                        .QueryWrapper<com.agv.navdeployer.entity.RmsTaskRecord>()
                        .in("status", "IDLE", "RUNNING", "PAUSED"));
                for (var record : pending) {
                    String originalStatus = record.getStatus();
                    record.setStatus("FAILED");
                    record.setMessage("上位机重启，任务中断收口（原状态 " + originalStatus + "）");
                    record.setFinishedAt(java.time.LocalDateTime.now());
                    mapper.updateById(record);
                    log.warn("rms task history reconciled on restart: task_id={} -> FAILED", record.getTaskId());
                }
            } catch (Exception exception) {
                log.error("rms task history reconciliation failed (startup continues): {}",
                        exception.toString());
            }
        };
    }
}
