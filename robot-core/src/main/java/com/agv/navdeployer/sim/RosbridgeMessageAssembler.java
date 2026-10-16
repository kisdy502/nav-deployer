package com.agv.navdeployer.sim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * rosbridge 协议消息组装器：
 * - 普通消息直接解析返回；
 * - 大消息会被 rosbridge 拆成 op=fragment 的分片（按 id 分组、按 num 顺序拼接 data），
 *   全部到齐后返回重组完成的完整消息，未到齐返回 null。
 * 线程安全（WebSocket IO 单线程访问，仍按 synchronized 处理以支持测试并发）。
 */
public final class RosbridgeMessageAssembler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, TreeMap<Integer, String>> fragmentsById = new HashMap<>();
    private final Map<String, Integer> totalsById = new HashMap<>();

    /**
     * 喂入一条原始文本帧。
     *
     * @return 完整的协议消息；分片未收齐时返回 null
     * @throws JsonProcessingException 原始帧不是合法 JSON
     */
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

    /** 当前未完成的分片组数量（观测/测试用）。 */
    public synchronized int pendingFragmentGroups() {
        return fragmentsById.size();
    }
}
