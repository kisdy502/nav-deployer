package com.agv.navdeployer.map.application;

import com.agv.navdeployer.map.domain.MapMeta;
import com.agv.navdeployer.map.infrastructure.repository.mapper.MapMetaMapper;
import org.springframework.stereotype.Component;

@Component
public class MapVersionTicker {

    private final MapMetaMapper mapMetaMapper;

    public MapVersionTicker(MapMetaMapper mapMetaMapper) {
        this.mapMetaMapper = mapMetaMapper;
    }

    public void touch(Long mapId, String modifiedBy) {
        if (mapId == null) {
            return;
        }
        MapMeta map = mapMetaMapper.selectById(mapId);
        if (map == null) {
            return;
        }
        map.setVersion(nextVersion(map.getVersion()));
        if (modifiedBy != null && !modifiedBy.isBlank()) {
            map.setModifiedBy(modifiedBy.trim());
        }
        map.setUpdatedAt(null); // 触发自动填充
        mapMetaMapper.updateById(map);
    }

    public static String nextVersion(String current) {
        int major = 0;
        int minor = 0;
        int patch = 0;
        if (current != null && !current.isBlank()) {
            String body = current.trim();
            if (body.startsWith("v") || body.startsWith("V")) {
                body = body.substring(1);
            }
            String[] parts = body.split("\\.");
            try {
                major = parts.length > 0 && !parts[0].isBlank() ? Integer.parseInt(parts[0]) : 0;
                minor = parts.length > 1 && !parts[1].isBlank() ? Integer.parseInt(parts[1]) : 0;
                patch = parts.length > 2 && !parts[2].isBlank() ? Integer.parseInt(parts[2]) : 0;
            } catch (NumberFormatException ignored) {
                major = 0;
                minor = 0;
                patch = 0;
            }
        }
        patch++;
        if (patch > 9) {
            patch = 0;
            minor++;
        }
        if (minor > 9) {
            minor = 0;
            major++;
        }
        return "v" + major + "." + minor + "." + patch;
    }
}
