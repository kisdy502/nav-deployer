package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * /map（nav_msgs/OccupancyGrid）实时缓存：保留最新一帧栅格，供建图快照与实时预览接口使用。
 * 消息由 RosbridgeClient 订阅后分派到这里（分片已重组），解析运行在 WS IO 线程，仅做字段拷贝。
 */
public class LiveMapCache implements RosbridgeHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveMapCache.class);

    private final String mapTopic;
    private volatile OccupancyGrid latest;

    public LiveMapCache(String mapTopic) {
        this.mapTopic = mapTopic;
    }

    public String topic() {
        return mapTopic;
    }

    /** 最新一帧栅格（快照本身不可变），尚未收到时为 null。 */
    public OccupancyGrid snapshot() {
        return latest;
    }

    @Override
    public void onMessage(JsonNode message) {
        if (!"publish".equals(message.path("op").asText("")) || !mapTopic.equals(message.path("topic").asText())) {
            return;
        }
        JsonNode msg = message.path("msg");
        OccupancyGrid grid = parse(msg);
        if (grid != null) {
            this.latest = grid;
        }
    }

    /** 解析 OccupancyGrid：info（resolution/width/height/origin）+ data（int8 行优先数组）。 */
    public static OccupancyGrid parse(JsonNode msg) {
        JsonNode info = msg.path("info");
        JsonNode data = msg.path("data");
        if (!info.isObject() || !data.isArray() || data.isEmpty()) {
            log.debug("ignore malformed occupancy grid: info.ok={}, data.size={}",
                    info.isObject(), data.size());
            return null;
        }
        int width = info.path("width").asInt(0);
        int height = info.path("height").asInt(0);
        double resolution = info.path("resolution").asDouble(0.0);
        if (width <= 0 || height <= 0 || resolution <= 0.0 || data.size() != width * height) {
            log.warn("ignore occupancy grid with inconsistent geometry: {}x{} res={} data={}",
                    width, height, resolution, data.size());
            return null;
        }
        JsonNode origin = info.path("origin");
        JsonNode position = origin.path("position");
        double yaw = SimAgvTelemetryCollector.yawFromQuaternion(
                origin.path("orientation").path("x").asDouble(),
                origin.path("orientation").path("y").asDouble(),
                origin.path("orientation").path("z").asDouble(),
                origin.path("orientation").path("w").asDouble(1.0));
        int[] cells = new int[data.size()];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = data.get(i).asInt();
        }
        return new OccupancyGrid(
                msg.path("header").path("frame_id").asText("map"),
                resolution, width, height,
                position.path("x").asDouble(),
                position.path("y").asDouble(),
                yaw,
                cells,
                Instant.now());
    }

    /**
     * 一帧占用栅格。data 语义：-1 未知、0 空闲、1~100 占据；
     * 第 0 行在世界坐标最下方（y 轴向上），渲染时 py = height - 1 - row。
     */
    public record OccupancyGrid(
            String frameId,
            double resolution,
            int width,
            int height,
            double originX,
            double originY,
            double originYaw,
            int[] data,
            Instant receivedAt
    ) {
    }
}
