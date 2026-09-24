package com.agv.navdeployer.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** record DTO 的 Jackson 反序列化最小复现（不依赖 Spring 上下文）。 */
class RecordBindingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void robotControlDtoBinds() throws Exception {
        var dto = mapper.readValue("{\"action\":\"start\"}", RobotControlDTO.class);
        assertEquals("start", dto.action());
    }

    @Test
    void saveMapDtoBinds() throws Exception {
        var dto = mapper.readValue("{\"mapName\":\"probe_test\"}", SaveMapDTO.class);
        assertEquals("probe_test", dto.mapName());
    }

    @Test
    void robotMapImportDtoBinds() throws Exception {
        var dto = mapper.readValue("{\"robotMapName\":\"map0923\"}", RobotMapImportDTO.class);
        assertEquals("map0923", dto.robotMapName());
    }
}
