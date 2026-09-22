package com.agv.navdeployer.service;

import com.agv.navdeployer.dto.NavPointCreateDTO;
import com.agv.navdeployer.dto.NavPointFromPoseDTO;
import com.agv.navdeployer.dto.NavPointUpdateDTO;
import com.agv.navdeployer.entity.NavMap;
import com.agv.navdeployer.entity.NavPathEdge;
import com.agv.navdeployer.entity.NavPoint;
import com.agv.navdeployer.exception.NotFoundException;
import com.agv.navdeployer.mapper.NavMapMapper;
import com.agv.navdeployer.mapper.NavPathEdgeMapper;
import com.agv.navdeployer.mapper.NavPointMapper;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import com.agv.navdeployer.vo.NavPointVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 点位部署：CRUD + 把机器人当前位姿标记为点位（无页面时的实操建点方式）。
 */
@Service
public class NavPointService {

    private final NavPointMapper navPointMapper;
    private final NavMapMapper navMapMapper;
    private final NavPathEdgeMapper navPathEdgeMapper;
    private final SimAgvTelemetry telemetry;

    public NavPointService(NavPointMapper navPointMapper,
                           NavMapMapper navMapMapper,
                           NavPathEdgeMapper navPathEdgeMapper,
                           SimAgvTelemetry telemetry) {
        this.navPointMapper = navPointMapper;
        this.navMapMapper = navMapMapper;
        this.navPathEdgeMapper = navPathEdgeMapper;
        this.telemetry = telemetry;
    }

    @Transactional(rollbackFor = Exception.class)
    public NavPointVO create(NavPointCreateDTO dto) {
        requireMap(dto.mapId());
        requireCodeAvailable(dto.mapId(), dto.pointCode());
        NavPoint point = new NavPoint();
        point.setMapId(dto.mapId());
        point.setPointCode(dto.pointCode());
        point.setPointType(defaultType(dto.pointType()));
        point.setX(dto.x());
        point.setY(dto.y());
        point.setYaw(dto.yaw());
        point.setRemark(dto.remark());
        navPointMapper.insert(point);
        return NavPointVO.from(point);
    }

    /** 读 /tf→map 实时位姿建点。 */
    @Transactional(rollbackFor = Exception.class)
    public NavPointVO createFromCurrentPose(NavPointFromPoseDTO dto) {
        requireMap(dto.mapId());
        requireCodeAvailable(dto.mapId(), dto.pointCode());
        SimAgvTelemetry.PoseSnapshot pose = telemetry.getMapPose();
        if (pose == null) {
            throw new IllegalStateException("尚未收到机器人位姿（/tf 或 /agv/pose），无法标记当前点");
        }
        NavPoint point = new NavPoint();
        point.setMapId(dto.mapId());
        point.setPointCode(dto.pointCode());
        point.setPointType(defaultType(dto.pointType()));
        point.setX(pose.x());
        point.setY(pose.y());
        point.setYaw(pose.yaw());
        point.setRemark(dto.remark());
        navPointMapper.insert(point);
        return NavPointVO.from(point);
    }

    public List<NavPointVO> list(Long mapId) {
        if (mapId == null) {
            throw new IllegalArgumentException("map_id 不能为空");
        }
        return navPointMapper.selectList(Wrappers.lambdaQuery(NavPoint.class)
                        .eq(NavPoint::getMapId, mapId)
                        .orderByAsc(NavPoint::getId))
                .stream().map(NavPointVO::from).toList();
    }

    public NavPointVO get(Long id) {
        return NavPointVO.from(findOrThrow(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public NavPointVO update(Long id, NavPointUpdateDTO dto) {
        NavPoint point = findOrThrow(id);
        if (StringUtils.hasText(dto.pointCode()) && !dto.pointCode().equals(point.getPointCode())) {
            requireCodeAvailable(point.getMapId(), dto.pointCode());
            point.setPointCode(dto.pointCode());
        }
        if (StringUtils.hasText(dto.pointType())) {
            point.setPointType(dto.pointType());
        }
        if (dto.x() != null) {
            point.setX(dto.x());
        }
        if (dto.y() != null) {
            point.setY(dto.y());
        }
        if (dto.yaw() != null) {
            point.setYaw(dto.yaw());
        }
        if (dto.remark() != null) {
            point.setRemark(dto.remark());
        }
        point.setUpdatedAt(null);
        navPointMapper.updateById(point);
        return NavPointVO.from(point);
    }

    /** 删除点位（被路线边引用时拒绝）。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        NavPoint point = findOrThrow(id);
        long referenced = navPathEdgeMapper.selectCount(Wrappers.lambdaQuery(NavPathEdge.class)
                .eq(NavPathEdge::getSourcePointId, id)
                .or()
                .eq(NavPathEdge::getTargetPointId, id));
        if (referenced > 0) {
            throw new IllegalStateException("点位已被 " + referenced + " 条路线边引用，请先调整相关路线");
        }
        navPointMapper.deleteById(id);
    }

    public NavPoint requirePoint(Long id) {
        return findOrThrow(id);
    }

    private NavPoint findOrThrow(Long id) {
        NavPoint point = navPointMapper.selectById(id);
        if (point == null) {
            throw new NotFoundException("点位 " + id + " 不存在");
        }
        return point;
    }

    private void requireMap(Long mapId) {
        if (navMapMapper.selectById(mapId) == null) {
            throw new NotFoundException("地图 " + mapId + " 不存在");
        }
    }

    private void requireCodeAvailable(Long mapId, String pointCode) {
        if (navPointMapper.selectCount(Wrappers.lambdaQuery(NavPoint.class)
                .eq(NavPoint::getMapId, mapId)
                .eq(NavPoint::getPointCode, pointCode)) > 0) {
            throw new IllegalStateException("点位编码已存在: " + pointCode);
        }
    }

    private static String defaultType(String pointType) {
        return StringUtils.hasText(pointType) ? pointType : NavPoint.TYPE_NORMAL;
    }
}
