package com.agv.navdeployer.map.application.dto;

import com.agv.navdeployer.map.application.MetadataJson;
import com.agv.navdeployer.map.domain.MapZone;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.List;

public record ZoneDTO(Long id,
                     Long mapId,
                     Long floorId,
                     String zoneCode,
                     String name,
                     String zoneType,
                     Integer priority,
                     Double maxSpeed,
                     String shape,
                     List<double[]> vertices,
                     Double height,
                     RampInfoDTO rampInfo,
                     String description) {

    private static final TypeReference<List<double[]>> VERTICES_TYPE = new TypeReference<>() {
    };

    public static ZoneDTO from(MapZone zone) {
        return new ZoneDTO(zone.getId(), zone.getMapId(), zone.getFloorId(), zone.getZoneCode(),
                zone.getName(), zone.getZoneType(), zone.getPriority(), zone.getMaxSpeed(),
                zone.getShape(), parseVertices(zone.getVertices()), zone.getHeight(),
                parseJson(zone.getRampInfo(), RampInfoDTO.class), zone.getDescription());
    }

    public static List<double[]> parseVertices(String json) {
        List<double[]> parsed = parseJson(json, VERTICES_TYPE);
        return parsed == null ? List.of() : parsed;
    }

    public static <T> T parseJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MetadataJson.MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 载荷解析失败: " + e.getMessage(), e);
        }
    }

    public static <T> T parseJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MetadataJson.MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 载荷解析失败: " + e.getMessage(), e);
        }
    }
}
