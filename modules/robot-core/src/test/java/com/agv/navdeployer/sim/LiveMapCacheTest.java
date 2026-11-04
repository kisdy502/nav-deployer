package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * /map OccupancyGrid 解析测试：正常栅格、几何不一致拒收、缓存命中。
 */
class LiveMapCacheTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 正常 2x2 OccupancyGrid 报文可完整解析出 frame、分辨率、尺寸、原点与栅格数据 */
    @Test
    void parseValidOccupancyGrid() throws Exception {
        String raw = """
                {"header":{"frame_id":"map"},
                 "info":{"resolution":0.05,"width":2,"height":2,
                          "origin":{"position":{"x":-10.0,"y":-10.0,"z":0.0},
                                    "orientation":{"x":0.0,"y":0.0,"z":0.0,"w":1.0}}},
                 "data":[0,100,-1,50]}
                """;
        LiveMapCache.OccupancyGrid grid = LiveMapCache.parse(MAPPER.readTree(raw));

        assertNotNull(grid);
        assertEquals("map", grid.frameId());
        assertEquals(0.05, grid.resolution(), 1e-9);
        assertEquals(2, grid.width());
        assertEquals(2, grid.height());
        assertEquals(-10.0, grid.originX(), 1e-9);
        assertEquals(0.0, grid.originYaw(), 1e-9);
        assertEquals(4, grid.data().length);
        assertEquals(0, grid.data()[0]);
        assertEquals(100, grid.data()[1]);
        assertEquals(-1, grid.data()[2]);
        assertEquals(50, grid.data()[3]);
    }

    /** data 长度与 width*height 不一致时拒收报文（返回 null） */
    @Test
    void rejectInconsistentGeometry() throws Exception {
        String raw = """
                {"info":{"resolution":0.05,"width":3,"height":2,
                          "origin":{"position":{"x":0,"y":0,"z":0},
                                    "orientation":{"w":1.0}}},
                 "data":[0,0,100,100]}
                """;
        assertNull(LiveMapCache.parse(MAPPER.readTree(raw)));
    }

    /** 缺少 data 数组的报文拒收（返回 null） */
    @Test
    void rejectMissingDataArray() throws Exception {
        String raw = """
                {"info":{"resolution":0.05,"width":2,"height":2,
                          "origin":{"position":{"x":0,"y":0,"z":0},
                                    "orientation":{"w":1.0}}}}
                """;
        assertNull(LiveMapCache.parse(MAPPER.readTree(raw)));
    }

    /** 仅 /map 话题的 publish 更新缓存快照，其他话题不覆盖已缓存的最新帧 */
    @Test
    void cacheKeepsLatestFrameOnTopicMatch() throws Exception {
        LiveMapCache cache = new LiveMapCache("/map");
        JsonNode publish = MAPPER.readTree("""
                {"op":"publish","topic":"/map","msg":{
                  "header":{"frame_id":"map"},
                  "info":{"resolution":0.05,"width":1,"height":1,
                          "origin":{"position":{"x":1,"y":2,"z":0},"orientation":{"w":1.0}}},
                  "data":[42]}}
                """);
        assertNull(cache.snapshot());
        cache.onMessage(publish);
        assertNotNull(cache.snapshot());
        assertEquals(42, cache.snapshot().data()[0]);

        // 其他话题不更新缓存
        cache.onMessage(MAPPER.readTree(
                "{\"op\":\"publish\",\"topic\":\"/agv/status\",\"msg\":{\"state\":\"IDLE\"}}"));
        assertEquals(42, cache.snapshot().data()[0]);
    }
}
