package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /agv/pose 双格式解析：标准 PoseStamped 与机器人自定义扁平格式
 * （顶层 x/y/yaw/qx~qw + frame_id + topo_name）。
 * 扁平格式的 topo_name = 机器人当前所在点位（离开点位后为空 → 透传侧置 null）。
 */
class SimAgvTelemetryCollectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SimAgvProperties props = new SimAgvProperties();
    private final SimAgvTelemetryCollector collector = new SimAgvTelemetryCollector(props);
    private final SimAgvTelemetry telemetry = collector.telemetry();

    private void publish(String topic, String msgJson) throws Exception {
        collector.onMessage(MAPPER.readTree(
                "{\"op\":\"publish\",\"topic\":\"" + topic + "\",\"msg\":" + msgJson + "}"));
    }

    /** 机器人自定义扁平格式（AgvPose）：位姿 + topo_name 都要采集 */
    @Test
    void flatAgvPoseSetsMapPoseAndTopoName() throws Exception {
        publish("/agv/pose", """
                {"x":1.1239996094753488,"y":-0.6410467608200641,"z":-0.42749329771603006,
                 "yaw":0.03813275169818326,
                 "qx":-0.0016850863507541479,"qy":-0.015696767403116485,
                 "qz":0.019031772854154347,"qw":0.9996942350534541,
                 "status":2,"timestamp":"1793178190564","frame_id":"map",
                 "topo_name":"Goal_SPFJY","update_time":"2026-10-28T09:37:36"}
                """);
        assertThat(telemetry.getMapPose()).isNotNull();
        assertThat(round(telemetry.getMapPose().x())).isEqualTo(1.124);
        assertThat(round(telemetry.getMapPose().y())).isEqualTo(-0.641);
        assertThat(telemetry.getMapPose().yaw()).isEqualTo(0.03813275169818326);
        // 扁平格式 timestamp 是墙钟 epoch-ms（与 /tf ROS 时间不同基）：不入历史环
        assertThat(telemetry.getMapPose().stampSec()).isZero();
        assertThat(telemetry.getTopoName()).isEqualTo("Goal_SPFJY");
    }

    /** 机器人离开点位：topo_name 空/缺失 → 清空 */
    @Test
    void flatAgvPoseWithoutTopoNameClearsIt() throws Exception {
        publish("/agv/pose", """
                {"x":2.0,"y":3.0,"yaw":0.5,"timestamp":"1793178190564","frame_id":"map","topo_name":"P33"}
                """);
        assertThat(telemetry.getTopoName()).isEqualTo("P33");
        publish("/agv/pose", """
                {"x":2.1,"y":3.1,"yaw":0.5,"timestamp":"1793178190564","frame_id":"map","topo_name":""}
                """);
        assertThat(telemetry.getTopoName()).isNull();
        publish("/agv/pose", """
                {"x":2.2,"y":3.2,"yaw":0.5,"timestamp":"1793178190564","frame_id":"map"}
                """);
        assertThat(telemetry.getTopoName()).isNull();
    }

    /** 标准 PoseStamped（pose.position/orientation + header.stamp）兼容保留 */
    @Test
    void standardPoseStampedStillParsed() throws Exception {
        publish("/agv/pose", """
                {"header":{"stamp":{"sec":1234,"nanosec":800000000},"frame_id":"map"},
                 "pose":{"position":{"x":-8.29,"y":-1.34,"z":0.0},
                         "orientation":{"x":0.0,"y":0.0,"z":0.089,"w":0.996}}}
                """);
        assertThat(telemetry.getMapPose()).isNotNull();
        assertThat(round(telemetry.getMapPose().x())).isEqualTo(-8.29);
        // header.stamp（ROS 仿真时间）入历史环
        assertThat(telemetry.getMapPose().stampSec()).isEqualTo(1234.8);
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }
}
