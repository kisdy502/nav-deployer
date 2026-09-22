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

    private com.fasterxml.jackson.databind.JsonNode statusMessage(String msgFields) {
        try {
            return MAPPER.readTree(
                    "{\"op\":\"publish\",\"topic\":\"/agv/status\",\"msg\":" + msgFields + "}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
