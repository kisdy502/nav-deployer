package com.agv.navdeployer.metadata.service;

import com.agv.navdeployer.metadata.entity.MapMeta;
import com.agv.navdeployer.metadata.mapper.MapMetaMapper;
import org.springframework.stereotype.Component;

/**
 * 地图版本自动递增：任何元数据变更（楼层/点位/区域/货架/路线/连接的增删改）都调用
 * {@link #touch}，版本号 +1（v0.0.9 → v0.1.0，进位规则：patch 超 9 归零进 minor，
 * minor 超 9 归零进 major），并刷新最后修改人与时间。
 *
 * <p>独立成组件避免 MapMetaService 与各子服务互相依赖形成环。
 */
@Component
public class MapVersionTicker {

    private final MapMetaMapper mapMetaMapper;

    public MapVersionTicker(MapMetaMapper mapMetaMapper) {
        this.mapMetaMapper = mapMetaMapper;
    }

    /** 元数据变更后调用；modifiedBy 为空时保留原值。 */
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

    /** v0.0.1 → v0.0.2 → … → v0.0.9 → v0.1.0；minor 超 9 同样进位到 major。 */
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
