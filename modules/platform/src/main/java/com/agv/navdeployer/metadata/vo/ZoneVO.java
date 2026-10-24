package com.agv.navdeployer.metadata.vo;

import com.agv.navdeployer.metadata.MetadataJson;
import com.agv.navdeployer.metadata.entity.MapZone;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.List;

/** 区域约束视图（vertices/rampInfo 从存库 JSON 解析）。 */
public record ZoneVO(Long id,
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
                     RampInfoVO rampInfo,
                     String description) {

    private static final TypeReference<List<double[]>> VERTICES_TYPE = new TypeReference<>() {
    };

    public static ZoneVO from(MapZone zone) {
        return new ZoneVO(zone.getId(), zone.getMapId(), zone.getFloorId(), zone.getZoneCode(),
                zone.getName(), zone.getZoneType(), zone.getPriority(), zone.getMaxSpeed(),
                zone.getShape(), parseVertices(zone.getVertices()), zone.getHeight(),
                parseJson(zone.getRampInfo(), RampInfoVO.class), zone.getDescription());
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
