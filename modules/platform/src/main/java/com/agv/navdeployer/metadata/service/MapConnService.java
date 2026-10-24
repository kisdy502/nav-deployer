package com.agv.navdeployer.metadata.service;

import com.agv.navdeployer.exception.NotFoundException;
import com.agv.navdeployer.metadata.dto.ConnCreateDTO;
import com.agv.navdeployer.metadata.dto.ConnEndpointDTO;
import com.agv.navdeployer.metadata.dto.ConnRampInfoDTO;
import com.agv.navdeployer.metadata.dto.ConnUpdateDTO;
import com.agv.navdeployer.metadata.entity.MapFloor;
import com.agv.navdeployer.metadata.entity.MapInterFloorConn;
import com.agv.navdeployer.metadata.entity.MapWaypoint;
import com.agv.navdeployer.metadata.mapper.MapFloorMapper;
import com.agv.navdeployer.metadata.mapper.MapInterFloorConnMapper;
import com.agv.navdeployer.metadata.mapper.MapWaypointMapper;
import com.agv.navdeployer.metadata.vo.ConnVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 楼层间连接管理：elevator / stairs / ramp，端点为楼层编码 + 点位编码。 */
@Service
public class MapConnService {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            MapInterFloorConn.TYPE_ELEVATOR, MapInterFloorConn.TYPE_STAIRS,
            MapInterFloorConn.TYPE_RAMP);

    private final MapInterFloorConnMapper connMapper;
    private final MapFloorMapper floorMapper;
    private final MapWaypointMapper waypointMapper;
    private final MapVersionTicker ticker;

    public MapConnService(MapInterFloorConnMapper connMapper,
                          MapFloorMapper floorMapper,
                          MapWaypointMapper waypointMapper,
                          MapVersionTicker ticker) {
        this.connMapper = connMapper;
        this.floorMapper = floorMapper;
        this.waypointMapper = waypointMapper;
        this.ticker = ticker;
    }

    @Transactional(rollbackFor = Exception.class)
    public ConnVO create(ConnCreateDTO dto) {
        String type = normalizeType(dto.connType());
        String code = dto.connCode().trim();
        if (connMapper.selectCount(Wrappers.lambdaQuery(MapInterFloorConn.class)
                .eq(MapInterFloorConn::getMapId, dto.mapId())
                .eq(MapInterFloorConn::getConnCode, code)) > 0) {
            throw new IllegalStateException("连接编码已存在: " + code);
        }
        validateEndpoints(dto.mapId(), dto.connections());

        MapInterFloorConn conn = new MapInterFloorConn();
        conn.setMapId(dto.mapId());
        conn.setConnCode(code);
        conn.setName(dto.name().trim());
        conn.setConnType(type);
        conn.setConnections(MapZoneService.toJson(dto.connections()));
        conn.setCapacity(dto.capacity());
        conn.setMaxSpeed(dto.maxSpeed());
        conn.setRampInfo(dto.rampInfo() == null ? null : MapZoneService.toJson(dto.rampInfo()));
        connMapper.insert(conn);
        ticker.touch(dto.mapId(), dto.modifiedBy());
        return ConnVO.from(conn);
    }

    @Transactional(rollbackFor = Exception.class)
    public ConnVO update(Long id, ConnUpdateDTO dto) {
        MapInterFloorConn conn = requireConn(id);
        if (dto.name() != null && !dto.name().isBlank()) {
            conn.setName(dto.name().trim());
        }
        if (dto.connType() != null) {
            conn.setConnType(normalizeType(dto.connType()));
        }
        if (dto.connections() != null && !dto.connections().isEmpty()) {
            validateEndpoints(conn.getMapId(), dto.connections());
            conn.setConnections(MapZoneService.toJson(dto.connections()));
        }
        if (dto.capacity() != null) {
            conn.setCapacity(dto.capacity());
        }
        if (dto.maxSpeed() != null) {
            conn.setMaxSpeed(dto.maxSpeed());
        }
        if (dto.rampInfo() != null) {
            conn.setRampInfo(MapZoneService.toJson(dto.rampInfo()));
        }
        conn.setUpdatedAt(null);
        connMapper.updateById(conn);
        ticker.touch(conn.getMapId(), dto.modifiedBy());
        return ConnVO.from(conn);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        MapInterFloorConn conn = requireConn(id);
        connMapper.deleteById(id);
        ticker.touch(conn.getMapId(), null);
    }

    public ConnVO get(Long id) {
        return ConnVO.from(requireConn(id));
    }

    public List<ConnVO> list(Long mapId) {
        return connMapper.selectList(Wrappers.lambdaQuery(MapInterFloorConn.class)
                        .eq(MapInterFloorConn::getMapId, mapId)
                        .orderByAsc(MapInterFloorConn::getId))
                .stream().map(ConnVO::from).toList();
    }

    private void validateEndpoints(Long mapId, List<ConnEndpointDTO> connections) {
        if (connections == null || connections.size() < 2) {
            throw new IllegalArgumentException("楼层间连接至少需要 2 个端点");
        }
        Set<String> floorCodes = new HashSet<>();
        floorMapper.selectList(Wrappers.lambdaQuery(MapFloor.class)
                        .eq(MapFloor::getMapId, mapId))
                .forEach(floor -> floorCodes.add(floor.getFloorCode()));
        Set<String> waypointCodes = new HashSet<>();
        waypointMapper.selectList(Wrappers.lambdaQuery(MapWaypoint.class)
                        .eq(MapWaypoint::getMapId, mapId))
                .forEach(waypoint -> waypointCodes.add(waypoint.getWaypointCode()));
        Set<String> usedFloors = new HashSet<>();
        for (ConnEndpointDTO endpoint : connections) {
            if (!floorCodes.contains(endpoint.floorId())) {
                throw new IllegalArgumentException("端点楼层不存在: " + endpoint.floorId());
            }
            if (!waypointCodes.contains(endpoint.waypointId())) {
                throw new IllegalArgumentException("端点点位不存在: " + endpoint.waypointId());
            }
            if (!usedFloors.add(endpoint.floorId())) {
                throw new IllegalArgumentException("同一楼层出现多个端点: " + endpoint.floorId());
            }
        }
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("连接类型不能为空");
        }
        for (String supported : SUPPORTED_TYPES) {
            if (supported.equalsIgnoreCase(type.trim())) {
                return supported;
            }
        }
        throw new IllegalArgumentException("不支持的连接类型: " + type + "（可选 elevator/stairs/ramp）");
    }

    private MapInterFloorConn requireConn(Long id) {
        MapInterFloorConn conn = connMapper.selectById(id);
        if (conn == null) {
            throw new NotFoundException("楼层间连接 " + id + " 不存在");
        }
        return conn;
    }
}
