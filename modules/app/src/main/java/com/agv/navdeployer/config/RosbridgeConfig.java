package com.agv.navdeployer.config;

import com.agv.navdeployer.robot.domain.ArmStateMonitor;
import com.agv.navdeployer.robot.infrastructure.LiveMapCache;
import com.agv.navdeployer.robot.infrastructure.MapSubscriptionGuard;
import com.agv.navdeployer.robot.infrastructure.RosCommandDispatcher;
import com.agv.navdeployer.robot.infrastructure.RosbridgeClient;
import com.agv.navdeployer.robot.domain.SimAgvProperties;
import com.agv.navdeployer.robot.domain.SimAgvTelemetry;
import com.agv.navdeployer.robot.infrastructure.SimAgvTelemetryCollector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(SimAgvProperties.class)
public class RosbridgeConfig {

    private static final Logger log = LoggerFactory.getLogger(RosbridgeConfig.class);

    @Bean
    public SimAgvTelemetryCollector simAgvTelemetryCollector(SimAgvProperties props) {
        return new SimAgvTelemetryCollector(props);
    }

    @Bean
    public SimAgvTelemetry simAgvTelemetry(SimAgvTelemetryCollector collector) {
        return collector.telemetry();
    }

    @Bean
    public LiveMapCache liveMapCache(SimAgvProperties props, ObjectMapper mapper,
                                    @Value("${map2d.use-rosbridge:false}") boolean legacy) {
        if (legacy || !props.isEnabled() || !props.isMapEnabled()) {
            return new LiveMapCache(props.getMapTopic());
        }
        return new LiveMapCache(props.getMapTopic(), props.robotHttpUri(8092).resolve("/maps/live"), mapper);
    }

    @Bean
    public RosCommandDispatcher rosCommandDispatcher(SimAgvProperties props) {
        return new RosCommandDispatcher(props);
    }

    @Bean
    public com.agv.navdeployer.robot.infrastructure.LocalizationOperationTracker localizationOperationTracker(
            SimAgvProperties props, RosCommandDispatcher dispatcher) {
        com.agv.navdeployer.robot.infrastructure.LocalizationOperationTracker tracker =
                new com.agv.navdeployer.robot.infrastructure.LocalizationOperationTracker(props);
        tracker.attachQueryCaller(dispatcher::queryLocalizationOperation);
        return tracker;
    }

    @Bean
    public ArmStateMonitor armStateMonitor(SimAgvTelemetry telemetry,
                                           RosCommandDispatcher dispatcher,
                                           SimAgvProperties props) {
        return new ArmStateMonitor(telemetry, dispatcher::getArmMoveInFlight, props);
    }

    @Bean
    public MapSubscriptionGuard mapSubscriptionGuard(SimAgvProperties props) {
        return new MapSubscriptionGuard(props);
    }

    @Bean(destroyMethod = "close")
    public RosbridgeClient rosbridgeClient(SimAgvProperties props,
                                           SimAgvTelemetryCollector telemetryCollector,
                                           RosCommandDispatcher commandDispatcher,
                                           MapSubscriptionGuard mapSubscriptionGuard,
                                           com.agv.navdeployer.robot.infrastructure.LocalizationOperationTracker localizationOperationTracker) {
        // 指令/遥测主通道：不承载 /map——大消息会阻塞 rosbridge 单线程事件循环，
        // 把 /cmd_vel 与 /agv/status 挤出数百 ms（遥控迟钝的主因），/map 走专用通道
        RosbridgeClient client = new RosbridgeClient(
                props.getWsUrl(), props.getReconnectDelayMs(),
                telemetryCollector, commandDispatcher, mapSubscriptionGuard,
                localizationOperationTracker);
        commandDispatcher.attach(client);
        return client;
    }

    @Bean(destroyMethod = "close")
    public RosbridgeClient mapRosbridgeClient(SimAgvProperties props, LiveMapCache liveMapCache) {
        return new RosbridgeClient(props.getWsUrl(), props.getReconnectDelayMs(), liveMapCache);
    }

    @Bean
    public ApplicationRunner rosbridgeBootstrap(SimAgvProperties props,
                                                RosbridgeClient rosbridgeClient,
                                                RosbridgeClient mapRosbridgeClient,
                                                MapSubscriptionGuard mapSubscriptionGuard,
                                                @Value("${map2d.use-rosbridge:false}") boolean legacy) {
        return args -> {
            com.agv.navdeployer.robot.infrastructure.RosbridgeMessageLog.configure(
                    props.isWsMessageLogCommandsEnabled(), props.isWsMessageLogEnabled(),
                    props.getWsMessageLogMaxChars(), props.getWsMessageLogFullChars(),
                    props.getWsMessageLogTopicIntervalMs(), props.getWsMessageLogUnchangedIntervalMs(),
                    props.getWsMessageLogSensorIntervalMs(),
                    java.util.Set.of(props.getStatusTopic(), props.getLocalizationOperationTopic()));
            if (!props.isEnabled()) {
                log.info("sim-agv disabled, rosbridge client stays idle (ws={})", props.getWsUrl());
                return;
            }
                        rosbridgeClient.advertise(props.getCmdVelTopic(), "geometry_msgs/msg/Twist");
            // 全部订阅带显式消息类型：连接早于机器人侧 advertise 时 rosbridge
            // 类型推断失败不重试（/agv/status 失明 → 模式闸门误判），显式类型免疫该竞态
            rosbridgeClient.subscribe(props.getStatusTopic(), props.getStatusTopicType(), 1000, 0);
            rosbridgeClient.subscribe(props.getPoseTopic(), props.getPoseTopicType(), 0, 0);
            rosbridgeClient.subscribe(props.getTfTopic(), props.getTfTopicType(), props.getTfThrottleMs(), 0);
            if (props.isOdomEnabled()) {
                rosbridgeClient.subscribe(props.getOdomTopic(), props.getOdomTopicType(), 0, 0);
            }
            if (props.isScanEnabled()) {
                rosbridgeClient.subscribe(props.getScanTopic1(), props.getScanTopicType(), props.getScanThrottleMs(), 0);
                rosbridgeClient.subscribe(props.getScanTopic2(), props.getScanTopicType(), props.getScanThrottleMs(), 0);
                // transient_local：/tf_static 是 latched 话题，不声明收不到历史消息，
                // 机器人自描述安装变换就丢了（换机器人要同步改上位机 yaml 的根因）
                rosbridgeClient.subscribe(props.getTfStaticTopic(), props.getTfTopicType(), 0, 0, true);
            }
            if (props.isJointStatesEnabled()) {
                rosbridgeClient.subscribe(props.getJointStatesTopic(), props.getJointStatesTopicType(), 100, 0);
            }
            // 定位操作闭环（切图/重定位的进度与终态）：reliable + transient_local，
            // 连接后立即收到 latched 的最新操作，约每秒还有心跳重发，500ms 轮询消费不丢终态
            rosbridgeClient.subscribe(props.getLocalizationOperationTopic(),
                    props.getLocalizationOperationTopicType(), 0, 0, true);
            if (legacy) {
                mapSubscriptionGuard.attach(mapRosbridgeClient);
                mapRosbridgeClient.connect();
            }
            rosbridgeClient.connect();
        };
    }
}
