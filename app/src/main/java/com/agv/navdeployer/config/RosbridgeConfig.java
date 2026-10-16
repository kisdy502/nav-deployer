package com.agv.navdeployer.config;

import com.agv.navdeployer.sim.LiveMapCache;
import com.agv.navdeployer.sim.MapSubscriptionGuard;
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

    /** /map 按需订阅闸门（MAPPING 模式或 /live 被轮询时才挂载，降低 rosbridge 小消息被挤的延迟）。 */
    @Bean
    public MapSubscriptionGuard mapSubscriptionGuard(SimAgvProperties props) {
        return new MapSubscriptionGuard(props);
    }

    @Bean(destroyMethod = "close")
    public RosbridgeClient rosbridgeClient(SimAgvProperties props,
                                           SimAgvTelemetryCollector telemetryCollector,
                                           RosCommandDispatcher commandDispatcher,
                                           MapSubscriptionGuard mapSubscriptionGuard) {
        // 指令/遥测主通道：不承载 /map——大消息会阻塞 rosbridge 单线程事件循环，
        // 把 /cmd_vel 与 /agv/status 挤出数百 ms（遥控迟钝的主因），/map 走专用通道
        RosbridgeClient client = new RosbridgeClient(
                props.getWsUrl(), props.getReconnectDelayMs(),
                telemetryCollector, commandDispatcher, mapSubscriptionGuard);
        commandDispatcher.attach(client);
        return client;
    }

    /** /map 专用通道：2~3MB 大消息与指令通道物理隔离，建图期间遥控也不受影响。 */
    @Bean(destroyMethod = "close")
    public RosbridgeClient mapRosbridgeClient(SimAgvProperties props, LiveMapCache liveMapCache) {
        return new RosbridgeClient(props.getWsUrl(), props.getReconnectDelayMs(), liveMapCache);
    }

    /** 按 props 注册订阅与 advertise，最后建立连接（断线重连后自动恢复）。 */
    @Bean
    public ApplicationRunner rosbridgeBootstrap(SimAgvProperties props,
                                                RosbridgeClient rosbridgeClient,
                                                RosbridgeClient mapRosbridgeClient,
                                                MapSubscriptionGuard mapSubscriptionGuard) {
        return args -> {
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
                // 雷达安装变换（base_link → laser，平移+旋转）：点云投影的优先来源，缺失时回退 yaml 安装角
                // transient_local：/tf_static 是 latched 话题，不声明收不到历史消息，
                // 机器人自描述安装变换就丢了（换机器人要同步改上位机 yaml 的根因）
                rosbridgeClient.subscribe(props.getTfStaticTopic(), props.getTfTopicType(), 0, 0, true);
            }
            // /map 由 MapSubscriptionGuard 在 MAPPING 模式或 /live 被轮询时
            // 挂载到专用通道（mapRosbridgeClient），主通道永远不见大消息
            mapSubscriptionGuard.attach(mapRosbridgeClient);
            rosbridgeClient.connect();
            mapRosbridgeClient.connect();
        };
    }
}
