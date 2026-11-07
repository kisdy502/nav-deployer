package com.agv.navdeployer.robot.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public final class RosbridgeMessageAssembler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, TreeMap<Integer, String>> fragmentsById = new HashMap<>();
    private final Map<String, Integer> totalsById = new HashMap<>();

    public synchronized JsonNode add(String raw) throws JsonProcessingException {
        JsonNode root = MAPPER.readTree(raw);
        if (!"fragment".equals(root.path("op").asText())) {
            return root;
        }
        String id = root.path("id").asText(null);
        int num = root.path("num").asInt(-1);
        int total = root.path("total").asInt(-1);
        String data = root.path("data").asText("");
        if (id == null || num < 0 || total <= 0) {
            throw new JsonProcessingException("invalid fragment message: " + raw) {
            };
        }
        totalsById.putIfAbsent(id, total);
        fragmentsById.computeIfAbsent(id, key -> new TreeMap<>()).put(num, data);

        TreeMap<Integer, String> chunks = fragmentsById.get(id);
        if (chunks.size() < totalsById.get(id)) {
            return null;
        }
        fragmentsById.remove(id);
        totalsById.remove(id);
        StringBuilder whole = new StringBuilder();
        chunks.values().forEach(whole::append);
        return MAPPER.readTree(whole.toString());
    }

    public synchronized int pendingFragmentGroups() {
        return fragmentsById.size();
    }
}
