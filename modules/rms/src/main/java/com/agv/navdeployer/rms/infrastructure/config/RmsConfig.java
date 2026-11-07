package com.agv.navdeployer.rms.infrastructure.config;

import com.agv.navdeployer.rms.domain.RmsTemplateStore;
import com.agv.navdeployer.rms.infrastructure.config.RmsProperties;
import com.agv.navdeployer.rms.infrastructure.log.RmsLogCollector;
import com.agv.navdeployer.rms.infrastructure.teach.RmsTeachCollector;

import com.agv.navdeployer.robot.infrastructure.NativeMapClient;

import com.agv.navdeployer.rms.application.RmsMapService;
import com.agv.navdeployer.rms.infrastructure.gateway.RmsCommandGateway;
import com.agv.navdeployer.rms.infrastructure.gateway.RmsReportGateway;
import com.agv.navdeployer.rms.infrastructure.gateway.RmsServiceGateway;
import com.agv.navdeployer.rms.infrastructure.inspection.RmsInspectionUploader;
import com.agv.navdeployer.rms.domain.RmsMapState;
import com.agv.navdeployer.rms.application.RmsTelemetryService;
import com.agv.navdeployer.rms.infrastructure.protocol.keys.RmsCommandKeys;
import com.agv.navdeployer.rms.infrastructure.protocol.keys.RmsReportKeys;
import com.agv.navdeployer.rms.infrastructure.protocol.keys.RmsServiceKeys;
import com.agv.navdeployer.rms.infrastructure.session.RmsRobotSession;
import com.agv.navdeployer.rms.application.RobotStateView;
import com.agv.navdeployer.rms.domain.RmsResultReporter;
import com.agv.navdeployer.rms.application.RmsTaskService;
import com.agv.navdeployer.task.application.MapModeTaskService;
import com.agv.navdeployer.robot.domain.SimAgvProperties;
import com.agv.navdeployer.robot.domain.SimAgvTelemetry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public com.agv.navdeployer.rms.domain.RmsTemplateStore rmsTemplateStore(RmsProperties props) {
        String dir = props.getBehavior().getTemplateDir();
        java.nio.file.Path path = dir == null || dir.isBlank()
                ? null : java.nio.file.Path.of(dir.trim());
        com.agv.navdeployer.rms.domain.RmsTemplateStore store =
                new com.agv.navdeployer.rms.domain.RmsTemplateStore(path);
        log.info("rms template store ready: dir={}", path);
        return store;
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
                                               com.agv.navdeployer.rms.infrastructure.log.RmsLogCollector logCollector,
                                               com.agv.navdeployer.rms.infrastructure.teach.RmsTeachCollector teachCollector,
                                               RmsTaskService taskService,
                                               ObjectMapper mapper, RmsCommandKeys keys,
                                               RmsProperties rmsProperties) {
        return new RmsCommandGateway(taskService, mapService, telemetryService,
                inspectionUploader, logCollector, teachCollector,
                mapper, keys, rmsProperties.getReport().getPeriodicLogIntervalMs());
    }

    @Bean
    public com.agv.navdeployer.rms.infrastructure.log.RmsLogCollector rmsLogCollector(ObjectMapper mapper,
                                                                        RmsProperties properties) {
        return new com.agv.navdeployer.rms.infrastructure.log.RmsLogCollector(mapper, properties);
    }

    @Bean
    public com.agv.navdeployer.rms.infrastructure.teach.RmsTeachCollector rmsTeachCollector(ObjectMapper mapper,
                                                                              RmsProperties properties) {
        return new com.agv.navdeployer.rms.infrastructure.teach.RmsTeachCollector(mapper, properties);
    }

    @Bean
    public RmsInspectionUploader rmsInspectionUploader(ObjectMapper mapper,
                                                        RmsProperties properties,
                                                        com.agv.navdeployer.rms.domain.RmsTaskRegistry taskRegistry) {
        return new RmsInspectionUploader(mapper, properties, taskRegistry);
    }

    @Bean
    public RmsTelemetryService rmsTelemetryService(SimAgvTelemetry telemetry,
                                                   RmsProperties props, ObjectMapper mapper) {
        return new RmsTelemetryService(telemetry, props, mapper);
    }

    @Bean
    public RmsMapService rmsMapService(MapModeTaskService mapTaskService,
                                       SimAgvTelemetry telemetry,
                                       RmsMapState mapState,
                                       com.agv.navdeployer.map.application.MapMetaService mapMetaService,
                                       com.agv.navdeployer.map.application.MapPackageService mapPackageService,
                                       com.agv.navdeployer.robot.infrastructure.NativeMapClient nativeMaps) {
        return new RmsMapService(mapTaskService,
                telemetry, mapState, mapMetaService, mapPackageService, nativeMaps);
    }

    @Bean
    public com.agv.navdeployer.robot.infrastructure.NativeMapClient nativeMapClient(
            SimAgvProperties robot,
            @org.springframework.beans.factory.annotation.Value("${native-map.token:${NATIVE_MAP_TOKEN:}}") String nativeMapToken) {
        String baseUrl = nativeMapToken.isBlank() ? "" : robot.robotHttpUri(8091).toString();
        return new com.agv.navdeployer.robot.infrastructure.NativeMapClient(baseUrl, nativeMapToken);
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

    @Bean
    public ApplicationRunner rmsTaskHistoryReconciliation(
            com.agv.navdeployer.rms.infrastructure.repository.mapper.RmsTaskRecordMapper mapper) {
        return args -> {
            try {
                var pending = mapper.selectList(new com.baomidou.mybatisplus.core.conditions.query
                        .QueryWrapper<com.agv.navdeployer.rms.infrastructure.repository.RmsTaskRecord>()
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
