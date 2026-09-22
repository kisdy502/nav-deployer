package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * follow_edge goal 的 rosbridge JSON 契约测试（对齐 rosbridge_integration.md §5.1）。
 */
class RosCommandDispatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildFollowEdgeOpMatchesBridgeContract() throws Exception {
        RosCommandDispatcher.FollowEdgeGoal goal = new RosCommandDispatcher.FollowEdgeGoal(
                "move-abc-s1", "I006", -8.3, -1.35, 0.1787,
                "edge-1", "STRAIGHT", "I001", "I006",
                0.6, false, false, 0.1, true,
                List.of(new RosCommandDispatcher.ControlPoint(1.0, 2.0)));

        JsonNode op = RosCommandDispatcher.buildFollowEdgeOp(
                "goal-7", "/agv/follow_edge",
                "agv_bridge_v2_interfaces/action/FollowEdge", goal);

        assertEquals("send_action_goal", op.path("op").asText());
        assertEquals("goal-7", op.path("id").asText());
        assertEquals("/agv/follow_edge", op.path("action").asText());
        assertEquals("agv_bridge_v2_interfaces/action/FollowEdge", op.path("action_type").asText());
        assertTrue(op.path("feedback").asBoolean());

        JsonNode args = op.path("args");
        assertEquals("move-abc-s1", args.path("command_id").asText());
        assertEquals("I006", args.path("node_id").asText());
        assertEquals(-8.3, args.path("x").asDouble(), 1e-9);
        assertEquals(-1.35, args.path("y").asDouble(), 1e-9);
        assertEquals(0.1787, args.path("theta").asDouble(), 1e-9);
        assertEquals("STRAIGHT", args.path("edge_type").asText());
        assertEquals("I001", args.path("source_id").asText());
        assertEquals("I006", args.path("target_id").asText());
        assertEquals(0.6, args.path("max_speed").asDouble(), 1e-9);
        assertTrue(args.path("end_point").asBoolean());
        assertEquals(1, args.path("control_points").size());
        assertEquals(1.0, args.path("control_points").get(0).path("x").asDouble(), 1e-9);
        assertEquals(2.0, args.path("control_points").get(0).path("y").asDouble(), 1e-9);
    }

    @Test
    void nullStringFieldsFallBackToEmpty() throws Exception {
        RosCommandDispatcher.FollowEdgeGoal goal = new RosCommandDispatcher.FollowEdgeGoal(
                "move-x", null, 1, 2, 0, null, null, null, null,
                0.3, false, false, 0.1, false, List.of());

        JsonNode args = RosCommandDispatcher.buildFollowEdgeOp(
                "g", "a", "t", goal).path("args");

        assertEquals("", args.path("node_id").asText());
        assertEquals("", args.path("edge_id").asText());
        assertEquals("STRAIGHT", args.path("edge_type").asText());
        assertEquals("", args.path("source_id").asText());
        assertTrue(args.path("control_points").isEmpty());
    }

    @Test
    void buildCallServiceOpMatchesBridgeContract() throws Exception {
        JsonNode args = MAPPER.readTree("{\"map_name\":\"map0922\"}");

        JsonNode op = RosCommandDispatcher.buildCallServiceOp("svc-9", "/agv/load_map", args);
        assertEquals("call_service", op.path("op").asText());
        assertEquals("svc-9", op.path("id").asText());
        assertEquals("/agv/load_map", op.path("service").asText());
        assertEquals("map0922", op.path("args").path("map_name").asText());

        // null args -> 空对象（StartMapping/ListMaps 无请求参数）
        JsonNode noArgs = RosCommandDispatcher.buildCallServiceOp("svc-10", "/agv/list_maps", null);
        assertTrue(noArgs.path("args").isObject());
        assertEquals(0, noArgs.path("args").size());
    }
}
