package com.agv.navdeployer.config;

import com.agv.navdeployer.sim.LiveMapCache;
import com.agv.navdeployer.sim.RosCommandDispatcher;
import com.agv.navdeployer.sim.RosbridgeClient;
import com.agv.navdeployer.sim.SimAgvProperties;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import com.agv.navdeployer.sim.SimAgvTelemetryCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 仿真 AGV（rosbridge）装配：遥测采集 + 地图缓存 + 指令分发共用一条 WS 连接。
 * sim-agv.enabled=false 时仍创建 Bean（REST 可查遥测状态），只是不建立连接。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SimAgvProperties.class)
public class RosbridgeConfig {

    private static final Logger log = LoggerFactory.getLogger(RosbridgeConfig.class);

    @Bean
    public SimAgvTelemetryCollector simAgvTelemetryCollector(SimAgvProperties props) {
        return new SimAgvTelemetryCollector(props);
    }

    /** SSE 与 REST 快照读取的同一份遥测数据（由 collector 维护）。 */
    @Bean
    public SimAgvTelemetry simAgvTelemetry(SimAgvTelemetryCollector collector) {
        return collector.telemetry();
    }

    @Bean
    public LiveMapCache liveMapCache(SimAgvProperties props) {
        return new LiveMapCache(props.getMapTopic());
    }

    @Bean
    public RosCommandDispatcher rosCommandDispatcher(SimAgvProperties props) {
        return new RosCommandDispatcher(props);
    }

    @Bean(destroyMethod = "close")
    public RosbridgeClient rosbridgeClient(SimAgvProperties props,
                                           SimAgvTelemetryCollector telemetryCollector,
                                           LiveMapCache liveMapCache,
                                           RosCommandDispatcher commandDispatcher) {
        RosbridgeClient client = new RosbridgeClient(
                props.getWsUrl(), props.getReconnectDelayMs(),
                telemetryCollector, liveMapCache, commandDispatcher);
        commandDispatcher.attach(client);
        return client;
    }

    /** 按 props 注册订阅与 advertise，最后建立连接（断线重连后自动恢复）。 */
    @Bean
    public ApplicationRunner rosbridgeBootstrap(SimAgvProperties props,
                                                RosbridgeClient client,
                                                LiveMapCache liveMapCache) {
        return args -> {
            if (!props.isEnabled()) {
                log.info("sim-agv disabled, rosbridge client stays idle (ws={})", props.getWsUrl());
                return;
            }
            client.advertise(props.getInitialPoseTopic(), "geometry_msgs/msg/PoseWithCovarianceStamped");
            client.subscribe(props.getStatusTopic(), 1000);
            client.subscribe(props.getPoseTopic(), 0);
            client.subscribe(props.getTfTopic(), props.getTfThrottleMs());
            if (props.isOdomEnabled()) {
                client.subscribe(props.getOdomTopic(), 0);
            }
            if (props.isScanEnabled()) {
                client.subscribe(props.getScanTopic1(), props.getScanThrottleMs());
                client.subscribe(props.getScanTopic2(), props.getScanThrottleMs());
                // 雷达安装变换（base_link → laser，平移+旋转）：点云投影的优先来源，缺失时回退 yaml 安装角
                client.subscribe(props.getTfStaticTopic(), 0);
            }
            if (props.isMapEnabled()) {
                client.subscribe(props.getMapTopic(), props.getMapThrottleMs(), props.getMapFragmentSize());
                log.info("live map cache armed on topic {} (throttle={}ms, fragment={})",
                        liveMapCache.topic(), props.getMapThrottleMs(), props.getMapFragmentSize());
            }
            client.connect();
        };
    }
}
