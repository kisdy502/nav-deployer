package com.agv.navdeployer.map.application.dto;

import com.agv.navdeployer.map.domain.MapMeta;

import java.time.LocalDateTime;

public record MapV2DTO(Long id,
                      String mapName,
                      String coordinateSystem,
                      String unit,
                      String version,
                      String status,
                      String modifiedBy,
                      String source,
                      String robotMapName,
                      Double resolution,
                      Integer width,
                      Integer height,
                      Double originX,
                      Double originY,
                      Double originYaw,
                      Long dataSize,
                      LocalDateTime createdAt,
                      LocalDateTime updatedAt) {

    public static MapV2DTO from(MapMeta map) {
        return new MapV2DTO(map.getId(), map.getMapName(), map.getCoordinateSystem(), map.getUnit(),
                map.getVersion(), map.getStatus(), map.getModifiedBy(), map.getSource(),
                map.getRobotMapName(), map.getResolution(), map.getWidth(), map.getHeight(),
                map.getOriginX(), map.getOriginY(), map.getOriginYaw(), map.getDataSize(),
                map.getCreatedAt(), map.getUpdatedAt());
    }
}
