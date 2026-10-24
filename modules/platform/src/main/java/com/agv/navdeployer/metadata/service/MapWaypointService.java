package com.agv.navdeployer.metadata.service;

import com.agv.navdeployer.exception.NotFoundException;
import com.agv.navdeployer.metadata.dto.WaypointCreateDTO;
import com.agv.navdeployer.metadata.dto.WaypointUpdateDTO;
import com.agv.navdeployer.metadata.entity.MapFloor;
import com.agv.navdeployer.metadata.entity.MapInterFloorConn;
import com.agv.navdeployer.metadata.entity.MapPath;
import com.agv.navdeployer.metadata.entity.MapPathSegment;
import com.agv.navdeployer.metadata.entity.MapWaypoint;
import com.agv.navdeployer.metadata.mapper.MapFloorMapper;
import com.agv.navdeployer.metadata.mapper.MapInterFloorConnMapper;
import com.agv.navdeployer.metadata.mapper.MapMetaMapper;
import com.agv.navdeployer.metadata.mapper.MapPathMapper;
import com.agv.navdeployer.metadata.mapper.MapPathSegmentMapper;
import com.agv.navdeployer.metadata.mapper.MapWaypointMapper;
import com.agv.navdeployer.metadata.vo.WaypointVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/** 点位管理：类型扩展（charge/pickup/dropoff/storage/elevator/stairs/ramp/normal）。 */
@Service
public class MapWaypointService {

    public static final Set<String> SUPPORTED_TYPES = Set.of(
            MapWaypoint.TYPE_PATH, MapWaypoint.TYPE_CHARGE, MapWaypoint.TYPE_PICKUP,
            MapWaypoint.TYPE_DROPOFF, MapWaypoint.TYPE_STORAGE, MapWaypoint.TYPE_ELEVATOR,
            MapWaypoint.TYPE_STAIRS, MapWaypoint.TYPE_RAMP, MapWaypoint.TYPE_RELOCATION,
            MapWaypoint.TYPE_PARKING, MapWaypoint.TYPE_WORKSTATION,
            MapWaypoint.TYPE_DOCK, MapWaypoint.TYPE_ROLLER);

    private final MapWaypointMapper waypointMapper;
    private final MapFloorMapper floorMapper;
    private final MapMetaMapper mapMetaMapper;
    private final MapPathMapper pathMapper;
    private final MapPathSegmentMapper segmentMapper;
    private final MapInterFloorConnMapper connMapper;
    private final MapVersionTicker ticker;

    public MapWaypointService(MapWaypointMapper waypointMapper,
                              MapFloorMapper floorMapper,
                              MapMetaMapper mapMetaMapper,
                              MapPathMapper pathMapper,
                              MapPathSegmentMapper segmentMapper,
                              MapInterFloorConnMapper connMapper,
                              MapVersionTicker ticker) {
        this.waypointMapper = waypointMapper;
        this.floorMapper = floorMapper;
        this.mapMetaMapper = mapMetaMapper;
        this.pathMapper = pathMapper;
        this.segmentMapper = segmentMapper;
        this.connMapper = connMapper;
        this.ticker = ticker;
    }

    @Transactional(rollbackFor = Exception.class)
    public WaypointVO create(WaypointCreateDTO dto) {
        requireFloorOfMap(dto.floorId(), dto.mapId());
        String code = dto.waypointCode().trim();
        if (waypointMapper.selectCount(Wrappers.lambdaQuery(MapWaypoint.class)
                .eq(MapWaypoint::getMapId, dto.mapId())
                .eq(MapWaypoint::getWaypointCode, code)) > 0) {
            throw new IllegalStateException("点位编码已存在: " + code);
        }
        MapWaypoint waypoint = new MapWaypoint();
        waypoint.setMapId(dto.mapId());
        waypoint.setFloorId(dto.floorId());
        waypoint.setWaypointCode(code);
        waypoint.setName(dto.name() == null || dto.name().isBlank() ? code : dto.name().trim());
        waypoint.setWpType(normalizeType(dto.wpType()));
        waypoint.setX(dto.x());
        waypoint.setY(dto.y());
        waypoint.setYaw(dto.yaw() != null ? dto.yaw() : 0.0);
        waypoint.setRemark(dto.remark());
        waypointMapper.insert(waypoint);
        ticker.touch(dto.mapId(), dto.modifiedBy());
        return WaypointVO.from(waypoint);
    }

    @Transactional(rollbackFor = Exception.class)
    public WaypointVO update(Long id, WaypointUpdateDTO dto) {
        MapWaypoint waypoint = requireWaypoint(id);
        if (dto.name() != null) {
            waypoint.setName(dto.name().isBlank() ? waypoint.getWaypointCode() : dto.name().trim());
        }
        if (dto.wpType() != null) {
            waypoint.setWpType(normalizeType(dto.wpType()));
        }
        if (dto.x() != null) {
            waypoint.setX(dto.x());
        }
        if (dto.y() != null) {
            waypoint.setY(dto.y());
        }
        if (dto.yaw() != null) {
            waypoint.setYaw(dto.yaw());
        }
        if (dto.remark() != null) {
            waypoint.setRemark(dto.remark());
        }
        waypoint.setUpdatedAt(null);
        waypointMapper.updateById(waypoint);
        ticker.touch(waypoint.getMapId(), dto.modifiedBy());
        return WaypointVO.from(waypoint);
    }

    /** 删除点位：被路线（起终点/段）或楼层间连接引用时拒绝。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        MapWaypoint waypoint = requireWaypoint(id);
        if (pathMapper.selectCount(Wrappers.lambdaQuery(MapPath.class)
                .eq(MapPath::getMapId, waypoint.getMapId())
                .and(wrapper -> wrapper
                        .eq(MapPath::getFromWaypointId, id).or()
                        .eq(MapPath::getToWaypointId, id))) > 0
                || segmentMapper.selectCount(Wrappers.lambdaQuery(MapPathSegment.class)
                .and(wrapper -> wrapper
                        .eq(MapPathSegment::getSourceWaypointId, id).or()
                        .eq(MapPathSegment::getTargetWaypointId, id))) > 0) {
            throw new IllegalStateException("点位被路线引用，请先删除或调整相关路线: "
                    + waypoint.getWaypointCode());
        }
        String connReference = "\"waypointId\":\"" + waypoint.getWaypointCode() + "\"";
        List<MapInterFloorConn> conns = connMapper.selectList(Wrappers.lambdaQuery(MapInterFloorConn.class)
                .eq(MapInterFloorConn::getMapId, waypoint.getMapId()));
        for (MapInterFloorConn conn : conns) {
            if (conn.getConnections() != null && conn.getConnections().contains(connReference)) {
                throw new IllegalStateException("点位被楼层间连接 " + conn.getConnCode()
                        + " 引用，请先调整该连接");
            }
        }
        waypointMapper.deleteById(id);
        ticker.touch(waypoint.getMapId(), null);
    }

    public WaypointVO get(Long id) {
        return WaypointVO.from(requireWaypoint(id));
    }

    public List<WaypointVO> list(Long mapId) {
        return waypointMapper.selectList(Wrappers.lambdaQuery(MapWaypoint.class)
                        .eq(MapWaypoint::getMapId, mapId)
                        .orderByAsc(MapWaypoint::getId))
                .stream().map(WaypointVO::from).toList();
    }

    public List<WaypointVO> listByFloor(Long floorId) {
        return waypointMapper.selectList(Wrappers.lambdaQuery(MapWaypoint.class)
                        .eq(MapWaypoint::getFloorId, floorId)
                        .orderByAsc(MapWaypoint::getId))
                .stream().map(WaypointVO::from).toList();
    }

    public MapWaypoint requireWaypoint(Long id) {
        MapWaypoint waypoint = waypointMapper.selectById(id);
        if (waypoint == null) {
            throw new NotFoundException("点位 " + id + " 不存在");
        }
        return waypoint;
    }

    public String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return MapWaypoint.TYPE_PATH;
        }
        String normalized = type.trim();
        // 旧词表归一（早期版本用 normal，导入/迁移数据仍可能出现）
        if ("normal".equalsIgnoreCase(normalized)) {
            return MapWaypoint.TYPE_PATH;
        }
        for (String supported : SUPPORTED_TYPES) {
            if (supported.equalsIgnoreCase(normalized)) {
                return supported;
            }
        }
        throw new IllegalArgumentException("不支持的点位类型: " + type
                + "，可选: " + String.join("/", SUPPORTED_TYPES.stream().sorted().toList()));
    }

    private void requireFloorOfMap(Long floorId, Long mapId) {
        MapFloor floor = floorMapper.selectById(floorId);
        if (floor == null) {
            throw new NotFoundException("楼层 " + floorId + " 不存在");
        }
        if (!floor.getMapId().equals(mapId)) {
            throw new IllegalArgumentException("楼层 " + floorId + " 不属于地图 " + mapId);
        }
        if (mapMetaMapper.selectById(mapId) == null) {
            throw new NotFoundException("地图 " + mapId + " 不存在");
        }
    }
}
