package com.agv.navdeployer.metadata.service;

import com.agv.navdeployer.exception.NotFoundException;
import com.agv.navdeployer.metadata.dto.FloorCreateDTO;
import com.agv.navdeployer.metadata.dto.FloorUpdateDTO;
import com.agv.navdeployer.metadata.entity.MapFloor;
import com.agv.navdeployer.metadata.entity.MapMeta;
import com.agv.navdeployer.metadata.entity.MapPath;
import com.agv.navdeployer.metadata.entity.MapWaypoint;
import com.agv.navdeployer.metadata.mapper.MapFloorMapper;
import com.agv.navdeployer.metadata.mapper.MapMetaMapper;
import com.agv.navdeployer.metadata.mapper.MapPathMapper;
import com.agv.navdeployer.metadata.mapper.MapWaypointMapper;
import com.agv.navdeployer.metadata.vo.FloorVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 楼层管理：楼层增删改查；删除时仍有点位/路线则拒绝（区域/货架随库级联删除）。 */
@Service
public class MapFloorService {

    private final MapFloorMapper floorMapper;
    private final MapMetaMapper mapMetaMapper;
    private final MapWaypointMapper waypointMapper;
    private final MapPathMapper pathMapper;
    private final MapVersionTicker ticker;

    public MapFloorService(MapFloorMapper floorMapper,
                           MapMetaMapper mapMetaMapper,
                           MapWaypointMapper waypointMapper,
                           MapPathMapper pathMapper,
                           MapVersionTicker ticker) {
        this.floorMapper = floorMapper;
        this.mapMetaMapper = mapMetaMapper;
        this.waypointMapper = waypointMapper;
        this.pathMapper = pathMapper;
        this.ticker = ticker;
    }

    @Transactional(rollbackFor = Exception.class)
    public FloorVO create(FloorCreateDTO dto) {
        requireMap(dto.mapId());
        if (floorMapper.selectCount(Wrappers.lambdaQuery(MapFloor.class)
                .eq(MapFloor::getMapId, dto.mapId())
                .eq(MapFloor::getFloorCode, dto.floorCode().trim())) > 0) {
            throw new IllegalStateException("楼层标识已存在: " + dto.floorCode());
        }
        MapFloor floor = new MapFloor();
        floor.setMapId(dto.mapId());
        floor.setFloorCode(dto.floorCode().trim());
        floor.setName(dto.name().trim());
        floor.setElevation(dto.elevation());
        floor.setDefaultMaxSpeed(dto.defaultMaxSpeed());
        floor.setSortOrder(dto.sortOrder() != null ? dto.sortOrder() : 0);
        floorMapper.insert(floor);
        ticker.touch(dto.mapId(), dto.modifiedBy());
        return FloorVO.from(floor);
    }

    @Transactional(rollbackFor = Exception.class)
    public FloorVO update(Long id, FloorUpdateDTO dto) {
        MapFloor floor = requireFloor(id);
        if (dto.name() != null && !dto.name().isBlank()) {
            floor.setName(dto.name().trim());
        }
        if (dto.elevation() != null) {
            floor.setElevation(dto.elevation());
        }
        if (dto.defaultMaxSpeed() != null) {
            floor.setDefaultMaxSpeed(dto.defaultMaxSpeed());
        }
        if (dto.sortOrder() != null) {
            floor.setSortOrder(dto.sortOrder());
        }
        floor.setUpdatedAt(null);
        floorMapper.updateById(floor);
        ticker.touch(floor.getMapId(), dto.modifiedBy());
        return FloorVO.from(floor);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        MapFloor floor = requireFloor(id);
        if (waypointMapper.selectCount(Wrappers.lambdaQuery(MapWaypoint.class)
                .eq(MapWaypoint::getFloorId, id)) > 0) {
            throw new IllegalStateException("楼层下仍有点位，请先删除楼层内全部点位");
        }
        if (pathMapper.selectCount(Wrappers.lambdaQuery(MapPath.class)
                .eq(MapPath::getFloorId, id)) > 0) {
            throw new IllegalStateException("楼层下仍有路线，请先删除楼层内全部路线");
        }
        floorMapper.deleteById(id); // 区域/货架经外键级联删除
        ticker.touch(floor.getMapId(), null);
    }

    public FloorVO get(Long id) {
        return FloorVO.from(requireFloor(id));
    }

    public List<FloorVO> list(Long mapId) {
        return floorMapper.selectList(Wrappers.lambdaQuery(MapFloor.class)
                        .eq(MapFloor::getMapId, mapId)
                        .orderByAsc(MapFloor::getSortOrder).orderByAsc(MapFloor::getId))
                .stream().map(FloorVO::from).toList();
    }

    public MapFloor requireFloor(Long id) {
        MapFloor floor = floorMapper.selectById(id);
        if (floor == null) {
            throw new NotFoundException("楼层 " + id + " 不存在");
        }
        return floor;
    }

    /** 校验楼层存在且属于指定地图。 */
    public MapFloor requireFloorOfMap(Long floorId, Long mapId) {
        MapFloor floor = requireFloor(floorId);
        if (!floor.getMapId().equals(mapId)) {
            throw new IllegalArgumentException("楼层 " + floorId + " 不属于地图 " + mapId);
        }
        return floor;
    }

    private void requireMap(Long mapId) {
        if (mapMetaMapper.selectById(mapId) == null) {
            throw new NotFoundException("地图 " + mapId + " 不存在");
        }
    }
}
