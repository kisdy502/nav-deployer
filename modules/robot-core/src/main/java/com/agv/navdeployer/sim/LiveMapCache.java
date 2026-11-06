package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.GZIPInputStream;

public class LiveMapCache implements RosbridgeHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveMapCache.class);

    private final String mapTopic;
    private final URI httpEndpoint;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private volatile OccupancyGrid latest;
    private String etag;

    public LiveMapCache(String mapTopic) {
        this(mapTopic, null, null);
    }

    public LiveMapCache(String mapTopic, URI httpEndpoint, ObjectMapper mapper) {
        this.mapTopic = mapTopic;
        this.httpEndpoint = httpEndpoint;
        this.mapper = mapper;
        this.httpClient = httpEndpoint == null ? null : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build();
    }

    public String topic() {
        return mapTopic;
    }

    public synchronized OccupancyGrid snapshot() {
        if (httpEndpoint == null) {
            return latest;
        }
        var request = HttpRequest.newBuilder(httpEndpoint)
                .header("Accept-Encoding", "gzip").timeout(Duration.ofSeconds(3));
        if (etag != null) {
            request.header("If-None-Match", etag);
        }
        try {
            var response = httpClient.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            try (InputStream body = new ByteArrayInputStream(response.body())) {
                if (response.statusCode() == 304) {
                    return latest;
                }
                if (response.statusCode() != 200) {
                    return null;
                }
                try (InputStream decoded = response.headers().firstValue("Content-Encoding")
                        .filter("gzip"::equalsIgnoreCase).isPresent() ? new GZIPInputStream(body) : body) {
                    OccupancyGrid grid = parse(mapper.readTree(decoded));
                    if (grid != null) {
                        latest = grid;
                        etag = response.headers().firstValue("ETag").orElse(null);
                    }
                    return grid;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.debug("Could not fetch live occupancy grid over HTTP", e);
        }
        return null;
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
