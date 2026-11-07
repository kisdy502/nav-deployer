package com.agv.navdeployer.map.application;

import com.agv.navdeployer.common.error.NotFoundException;
import com.agv.navdeployer.map.application.MetadataJson;
import com.agv.navdeployer.map.application.cmd.RampInfoCmd;
import com.agv.navdeployer.map.application.cmd.ZoneCreateCmd;
import com.agv.navdeployer.map.application.cmd.ZoneUpdateCmd;
import com.agv.navdeployer.map.domain.MapFloor;
import com.agv.navdeployer.map.domain.MapZone;
import com.agv.navdeployer.map.infrastructure.repository.mapper.MapFloorMapper;
import com.agv.navdeployer.map.infrastructure.repository.mapper.MapZoneMapper;
import com.agv.navdeployer.map.application.dto.ZoneDTO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class MapZoneService {

    public static final Set<String> SUPPORTED_TYPES = Set.of(
            MapZone.TYPE_FORBIDDEN, MapZone.TYPE_SLOW_DOWN, MapZone.TYPE_RAMP);

    private final MapZoneMapper zoneMapper;
    private final MapFloorMapper floorMapper;
    private final MapVersionTicker ticker;

    public MapZoneService(MapZoneMapper zoneMapper,
                          MapFloorMapper floorMapper,
                          MapVersionTicker ticker) {
        this.zoneMapper = zoneMapper;
        this.floorMapper = floorMapper;
        this.ticker = ticker;
    }

    @Transactional(rollbackFor = Exception.class)
    public ZoneDTO create(ZoneCreateCmd dto) {
        requireFloorOfMap(dto.floorId(), dto.mapId());
        String type = normalizeType(dto.zoneType());
        RampInfoCmd rampInfo = requireRampInfo(type, dto.rampInfo());
        validateVertices(dto.vertices());
        String code = dto.zoneCode().trim();
        if (zoneMapper.selectCount(Wrappers.lambdaQuery(MapZone.class)
                .eq(MapZone::getMapId, dto.mapId())
                .eq(MapZone::getZoneCode, code)) > 0) {
            throw new IllegalStateException("区域编码已存在: " + code);
        }
        MapZone zone = new MapZone();
        zone.setMapId(dto.mapId());
        zone.setFloorId(dto.floorId());
        zone.setZoneCode(code);
        zone.setName(dto.name().trim());
        zone.setZoneType(type);
        zone.setPriority(dto.priority() != null ? dto.priority() : 2);
        zone.setMaxSpeed(resolveMaxSpeed(type, dto.maxSpeed()));
        zone.setShape("polygon");
        zone.setVertices(toJson(dto.vertices()));
        zone.setHeight(dto.height());
        zone.setRampInfo(rampInfo == null ? null : toJson(rampInfo));
        zone.setDescription(dto.description());
        zoneMapper.insert(zone);
        ticker.touch(dto.mapId(), dto.modifiedBy());
        return ZoneDTO.from(zone);
    }

    @Transactional(rollbackFor = Exception.class)
    public ZoneDTO update(Long id, ZoneUpdateCmd dto) {
        MapZone zone = requireZone(id);
        String type = dto.zoneType() == null ? zone.getZoneType() : normalizeType(dto.zoneType());
        RampInfoCmd rampInfo = requireRampInfo(type, dto.rampInfo());
        if (dto.vertices() != null && !dto.vertices().isEmpty()) {
            validateVertices(dto.vertices());
            zone.setVertices(toJson(dto.vertices()));
        }
        if (dto.name() != null && !dto.name().isBlank()) {
            zone.setName(dto.name().trim());
        }
        zone.setZoneType(type);
        if (dto.priority() != null) {
            zone.setPriority(dto.priority());
        }
        if (dto.maxSpeed() != null) {
            zone.setMaxSpeed(resolveMaxSpeed(type, dto.maxSpeed()));
        }
        if (dto.height() != null) {
            zone.setHeight(dto.height());
        }
        zone.setRampInfo(rampInfo == null ? null : toJson(rampInfo));
        if (dto.description() != null) {
            zone.setDescription(dto.description());
        }
        zone.setUpdatedAt(null);
        zoneMapper.updateById(zone);
        ticker.touch(zone.getMapId(), dto.modifiedBy());
        return ZoneDTO.from(zone);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        MapZone zone = requireZone(id);
        zoneMapper.deleteById(id);
        ticker.touch(zone.getMapId(), null);
    }

    public ZoneDTO get(Long id) {
        return ZoneDTO.from(requireZone(id));
    }

    public List<ZoneDTO> list(Long mapId) {
        return zoneMapper.selectList(Wrappers.lambdaQuery(MapZone.class)
                        .eq(MapZone::getMapId, mapId).orderByAsc(MapZone::getId))
                .stream().map(ZoneDTO::from).toList();
    }

    public List<ZoneDTO> listByFloor(Long floorId) {
        return zoneMapper.selectList(Wrappers.lambdaQuery(MapZone.class)
                        .eq(MapZone::getFloorId, floorId).orderByAsc(MapZone::getId))
                .stream().map(ZoneDTO::from).toList();
    }

    private MapZone requireZone(Long id) {
        MapZone zone = zoneMapper.selectById(id);
        if (zone == null) {
            throw new NotFoundException("区域 " + id + " 不存在");
        }
        return zone;
    }

    private RampInfoCmd requireRampInfo(String type, RampInfoCmd rampInfo) {
        if (MapZone.TYPE_RAMP.equals(type)) {
            if (rampInfo == null) {
                throw new IllegalArgumentException("坡道区域(type=ramp)必须携带 rampInfo");
            }
            return rampInfo;
        }
        return null;
    }

    private Double resolveMaxSpeed(String type, Double maxSpeed) {
        if (MapZone.TYPE_FORBIDDEN.equals(type)) {
            return 0.0;
        }
        return maxSpeed != null ? maxSpeed : 0.3;
    }

    private void validateVertices(List<double[]> vertices) {
        if (vertices == null || vertices.size() < 3) {
            throw new IllegalArgumentException("多边形区域至少需要 3 个顶点");
        }
        for (double[] vertex : vertices) {
            if (vertex == null || vertex.length != 2) {
                throw new IllegalArgumentException("区域顶点必须是 [x,y] 二维坐标");
            }
        }
    }

    public String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("区域类型不能为空");
        }
        String normalized = type.trim();
        if ("slow_down".equalsIgnoreCase(normalized) || "slowdown".equalsIgnoreCase(normalized)) {
            return MapZone.TYPE_SLOW_DOWN;
        }
        for (String supported : SUPPORTED_TYPES) {
            if (supported.equalsIgnoreCase(normalized)) {
                return supported;
            }
        }
        throw new IllegalArgumentException("不支持的区域类型: " + type + "，可选: forbidden/slowDown/ramp");
    }

    private void requireFloorOfMap(Long floorId, Long mapId) {
        MapFloor floor = floorMapper.selectById(floorId);
        if (floor == null) {
            throw new NotFoundException("楼层 " + floorId + " 不存在");
        }
        if (!floor.getMapId().equals(mapId)) {
            throw new IllegalArgumentException("楼层 " + floorId + " 不属于地图 " + mapId);
        }
    }

    public static String toJson(Object value) {
        try {
            return MetadataJson.MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 载荷序列化失败: " + e.getMessage(), e);
        }
    }
}
