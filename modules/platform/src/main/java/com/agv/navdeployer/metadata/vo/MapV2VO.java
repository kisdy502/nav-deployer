package com.agv.navdeployer.metadata.vo;

import com.agv.navdeployer.metadata.entity.MapMeta;

import java.time.LocalDateTime;

/** v2 地图视图。 */
public record MapV2VO(Long id,
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

    public static MapV2VO from(MapMeta map) {
        return new MapV2VO(map.getId(), map.getMapName(), map.getCoordinateSystem(), map.getUnit(),
                map.getVersion(), map.getStatus(), map.getModifiedBy(), map.getSource(),
                map.getRobotMapName(), map.getResolution(), map.getWidth(), map.getHeight(),
                map.getOriginX(), map.getOriginY(), map.getOriginYaw(), map.getDataSize(),
                map.getCreatedAt(), map.getUpdatedAt());
    }
}
