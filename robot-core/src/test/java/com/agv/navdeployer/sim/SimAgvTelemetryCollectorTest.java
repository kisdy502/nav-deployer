package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /agv/status 解析与镜像状态新鲜度测试（对齐 agv_bridge_v2 v0.3.0 的 mode/map_name）。
 */
class SimAgvTelemetryCollectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SimAgvProperties props = new SimAgvProperties();
    private final SimAgvTelemetryCollector collector = new SimAgvTelemetryCollector(props);

    @Test
    void statusMessageParsesModeAndMapName() throws Exception {
        collector.onMessage(MAPPER.readTree("""
                {"op":"publish","topic":"/agv/status","msg":{
                  "agv_id":"AGV001","state":"MOVING","battery":88.5,
                  "pose_initialized":true,
                  "active_command_id":"cmd-1","active_node_id":"I006",
                  "mode":"RELOCALIZING","map_name":"map0922"
                }}
                """));

        var status = collector.telemetry().getStatus();
        assertEquals("AGV001", status.agvId());
        assertEquals("MOVING", status.state());
        assertTrue(status.poseInitialized());
        assertEquals("cmd-1", status.activeCommandId());
        assertEquals("I006", status.activeNodeId());
        assertEquals("RELOCALIZING", status.mode());
        assertEquals("map0922", status.mapName());
    }

    @Test
    void legacyStatusWithoutModeFieldsYieldsNulls() throws Exception {
        collector.onMessage(MAPPER.readTree("""
                {"op":"publish","topic":"/agv/status","msg":{
                  "agv_id":"AGV001","state":"IDLE","battery":100.0,
                  "pose_initialized":false
                }}
                """));

        var status = collector.telemetry().getStatus();
        assertEquals("IDLE", status.state());
        assertNull(status.mode());
        assertNull(status.mapName());
    }

    @Test
    void freshnessRequiresConnectionAndRecentStatus() {
        SimAgvTelemetry telemetry = collector.telemetry();

        // 从未收到 status：不新鲜
        assertFalse(telemetry.isStatusFresh(5000));

        // 收到 status 但未连接：不新鲜
        collector.onMessage(statusMessage("{\"state\":\"IDLE\"}"));
        assertFalse(telemetry.isStatusFresh(5000));

        // 连接 + 新鲜 status：新鲜
        collector.onConnected();
        assertTrue(telemetry.isStatusFresh(5000));

        // status 过期（10s 前收到，阈值 5s）：不新鲜
        telemetry.setStatus(new SimAgvTelemetry.StatusSnapshot(
                "AGV001", "IDLE", 100.0, false, "", "",
                "NAVIGATION", "map0922", java.time.Instant.now().minusSeconds(10)));
        assertFalse(telemetry.isStatusFresh(5000));
    }

    @Test
    void scanPoseUsesMapOdomBaseFootprintChainAtCaptureTime() throws Exception {
        publishStaticTransforms(0.5, 0.0, 0.0);
        publishTf("map", "odom", 10, 2.0, 0.0, Math.PI / 2.0);
        publishTf("odom", "base_footprint", 10, 1.0, 0.0, Math.PI / 2.0);
        publishScan(10);

        var scan = collector.telemetry().getScan1();
        assertTrue(scan != null);
        // odom→base = (1, 0.5, 90°)，再与 map→odom=(2, 0, 90°) 复合。
        assertEquals(1.5, scan.pose().x(), 1.0E-9);
        assertEquals(1.0, scan.pose().y(), 1.0E-9);
        assertEquals(Math.PI, Math.abs(scan.pose().yaw()), 1.0E-9);
    }

    @Test
    void pendingScanWaitsUntilTheWholeSplitTfChainCoversItsStamp() throws Exception {
        publishStaticTransforms(0.0, 0.0, 0.0);
        publishTf("map", "odom", 10, 0.0, 0.0, 0.0);
        publishScan(10);
        assertNull(collector.telemetry().getScan1());

        // 只有 odom 段推进到 9s，不能因为 map 段已到 10s 就提前释放扫描。
        publishTf("odom", "base_footprint", 9, 0.0, 0.0, 0.0);
        assertNull(collector.telemetry().getScan1());

        publishTf("odom", "base_footprint", 10, 0.0, 0.0, 0.0);
        assertTrue(collector.telemetry().getScan1() != null);
        assertEquals(10.0, collector.telemetry().getScan1().pose().stampSec(), 1.0E-9);
    }

    @Test
    void establishedSplitTfNeverFallsBackToCoveredCompositePose() throws Exception {
        publishStaticTransforms(0.0, 0.0, 0.0);
        // 低频 bridge 复合位姿已经覆盖 11s。
        publishTf("map", "AGV001/base_link", 10, 100.0, 0.0, 0.0);
        publishTf("map", "odom", 10, 0.0, 0.0, 0.0);
        publishTf("odom", "base_footprint", 10, 0.0, 0.0, 0.0);
        publishTf("map", "AGV001/base_link", 12, 100.0, 0.0, 0.0);

        // 分段 TF 尚只到 10s，11s 扫描必须等待，不能回退到错误的复合位姿 x=100。
        publishScan(11);
        assertNull(collector.telemetry().getScan1());
        publishTf("map", "odom", 11, 1.0, 0.0, 0.0);
        assertNull(collector.telemetry().getScan1());
        publishTf("odom", "base_footprint", 11, 2.0, 0.0, 0.0);

        var scan = collector.telemetry().getScan1();
        assertTrue(scan != null);
        assertEquals(3.0, scan.pose().x(), 1.0E-9);
    }

    private void publishStaticTransforms(double baseDx, double baseDy, double baseYaw) throws Exception {
        collector.onMessage(MAPPER.readTree("""
                {"op":"publish","topic":"/tf_static","msg":{"transforms":[
                  %s,
                  %s
                ]}}
                """.formatted(
                transformJson("base_footprint", "base_link", 0, baseDx, baseDy, baseYaw),
                transformJson("base_link", "laser", 0, 0.0, 0.0, 0.0))));
    }

    private void publishTf(String parent, String child, int sec,
                           double x, double y, double yaw) throws Exception {
        collector.onMessage(MAPPER.readTree("""
                {"op":"publish","topic":"/tf","msg":{"transforms":[%s]}}
                """.formatted(transformJson(parent, child, sec, x, y, yaw))));
    }

    private void publishScan(int sec) throws Exception {
        collector.onMessage(MAPPER.readTree("""
                {"op":"publish","topic":"/scan_1","msg":{
                  "header":{"stamp":{"sec":%d,"nanosec":0},"frame_id":"laser"},
                  "angle_min":0.0,"angle_increment":1.0,
                  "range_min":0.1,"range_max":20.0,"ranges":[1.0]
                }}
                """.formatted(sec)));
    }

    private String transformJson(String parent, String child, int sec,
                                 double x, double y, double yaw) {
        double z = Math.sin(yaw / 2.0);
        double w = Math.cos(yaw / 2.0);
        return """
                {"header":{"stamp":{"sec":%d,"nanosec":0},"frame_id":"%s"},
                 "child_frame_id":"%s","transform":{
                   "translation":{"x":%s,"y":%s,"z":0.0},
                   "rotation":{"x":0.0,"y":0.0,"z":%s,"w":%s}}}
                """.formatted(sec, parent, child, x, y, z, w);
    }

    private com.fasterxml.jackson.databind.JsonNode statusMessage(String msgFields) {
        try {
            return MAPPER.readTree(
                    "{\"op\":\"publish\",\"topic\":\"/agv/status\",\"msg\":" + msgFields + "}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
