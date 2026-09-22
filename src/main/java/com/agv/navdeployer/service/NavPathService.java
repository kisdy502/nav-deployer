package com.agv.navdeployer.service;

import com.agv.navdeployer.dto.ControlPointDTO;
import com.agv.navdeployer.dto.NavPathCreateDTO;
import com.agv.navdeployer.dto.NavPathEdgesUpdateDTO;
import com.agv.navdeployer.dto.NavPathUpdateDTO;
import com.agv.navdeployer.dto.PathEdgeDTO;
import com.agv.navdeployer.entity.NavMap;
import com.agv.navdeployer.entity.NavPath;
import com.agv.navdeployer.entity.NavPathEdge;
import com.agv.navdeployer.entity.NavPoint;
import com.agv.navdeployer.exception.NotFoundException;
import com.agv.navdeployer.mapper.NavMapMapper;
import com.agv.navdeployer.mapper.NavPathEdgeMapper;
import com.agv.navdeployer.mapper.NavPathMapper;
import com.agv.navdeployer.mapper.NavPointMapper;
import com.agv.navdeployer.vo.NavPathVO;
import com.agv.navdeployer.vo.PathEdgeVO;
import com.agv.navdeployer.vo.PathEdgeVO.ControlPointVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路线部署：路线 = 有序边序列。替换边序列时做同图 / 连续性 / 曲线控制点校验，
 * 校验通过才允许 deploy；FOLLOW_PATH 任务只执行 DEPLOYED 路线。
 */
@Service
public class NavPathService {

    private static final Logger log = LoggerFactory.getLogger(NavPathService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ControlPointDTO>> CONTROL_POINTS_TYPE = new TypeReference<>() {
    };

    private final NavPathMapper navPathMapper;
    private final NavPathEdgeMapper navPathEdgeMapper;
    private final NavPointMapper navPointMapper;
    private final NavMapMapper navMapMapper;

    public NavPathService(NavPathMapper navPathMapper,
                          NavPathEdgeMapper navPathEdgeMapper,
                          NavPointMapper navPointMapper,
                          NavMapMapper navMapMapper) {
        this.navPathMapper = navPathMapper;
        this.navPathEdgeMapper = navPathEdgeMapper;
        this.navPointMapper = navPointMapper;
        this.navMapMapper = navMapMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public NavPathVO create(NavPathCreateDTO dto) {
        if (navMapMapper.selectById(dto.mapId()) == null) {
            throw new NotFoundException("地图 " + dto.mapId() + " 不存在");
        }
        if (navPathMapper.selectCount(Wrappers.lambdaQuery(NavPath.class)
                .eq(NavPath::getMapId, dto.mapId())
                .eq(NavPath::getPathCode, dto.pathCode())) > 0) {
            throw new IllegalStateException("路线编码已存在: " + dto.pathCode());
        }
        NavPath path = new NavPath();
        path.setMapId(dto.mapId());
        path.setPathCode(dto.pathCode());
        path.setPathName(dto.pathName());
        path.setStatus(NavPath.STATUS_DRAFT);
        navPathMapper.insert(path);
        return NavPathVO.from(path);
    }

    public List<NavPathVO> list(Long mapId) {
        if (mapId == null) {
            throw new IllegalArgumentException("map_id 不能为空");
        }
        return navPathMapper.selectList(Wrappers.lambdaQuery(NavPath.class)
                        .eq(NavPath::getMapId, mapId)
                        .orderByDesc(NavPath::getId))
                .stream().map(NavPathVO::from).toList();
    }

    /** 详情（含有序边 + 点位编码）。 */
    public NavPathVO get(Long id) {
        NavPath path = findOrThrow(id);
        return NavPathVO.from(path, loadEdgeVOs(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public NavPathVO update(Long id, NavPathUpdateDTO dto) {
        NavPath path = findOrThrow(id);
        if (dto.pathName() != null) {
            path.setPathName(dto.pathName());
        }
        if (StringUtils.hasText(dto.status())) {
            path.setStatus(dto.status());
        }
        path.setUpdatedAt(null);
        navPathMapper.updateById(path);
        return NavPathVO.from(path);
    }

    /** 整体替换边序列；替换后路线回到 DRAFT，需重新 deploy。 */
    @Transactional(rollbackFor = Exception.class)
    public NavPathVO replaceEdges(Long id, NavPathEdgesUpdateDTO dto) {
        NavPath path = findOrThrow(id);
        Map<Long, NavPoint> pointsOfMap = navPointMapper.selectList(
                        Wrappers.lambdaQuery(NavPoint.class).eq(NavPoint::getMapId, path.getMapId()))
                .stream().collect(Collectors.toMap(NavPoint::getId, Function.identity()));
        validateEdgeSequence(dto.edges(), pointsOfMap);

        navPathEdgeMapper.delete(Wrappers.lambdaQuery(NavPathEdge.class).eq(NavPathEdge::getPathId, id));
        int seq = 0;
        for (PathEdgeDTO edge : dto.edges()) {
            seq++;
            NavPathEdge entity = new NavPathEdge();
            entity.setPathId(id);
            entity.setSeq(seq);
            entity.setSourcePointId(edge.sourcePointId());
            entity.setTargetPointId(edge.targetPointId());
            entity.setEdgeType(StringUtils.hasText(edge.edgeType()) ? edge.edgeType() : NavPathEdge.TYPE_STRAIGHT);
            entity.setControlPoints(writeControlPoints(edge.controlPoints()));
            entity.setMaxSpeed(edge.maxSpeed());
            entity.setBackUp(Boolean.TRUE.equals(edge.backUp()));
            entity.setReverse(Boolean.TRUE.equals(edge.reverse()));
            navPathEdgeMapper.insert(entity);
        }
        path.setStatus(NavPath.STATUS_DRAFT);
        path.setUpdatedAt(null);
        navPathMapper.updateById(path);
        log.info("path {} edges replaced: {} segments, back to DRAFT", id, seq);
        return get(id);
    }

    /** 部署路线：要求已配置至少一条边。 */
    @Transactional(rollbackFor = Exception.class)
    public NavPathVO deploy(Long id) {
        NavPath path = findOrThrow(id);
        long edgeCount = navPathEdgeMapper.selectCount(
                Wrappers.lambdaQuery(NavPathEdge.class).eq(NavPathEdge::getPathId, id));
        if (edgeCount == 0) {
            throw new IllegalStateException("路线尚未配置边，请先 PUT /api/v1/nav-paths/" + id + "/edges");
        }
        path.setStatus(NavPath.STATUS_DEPLOYED);
        path.setUpdatedAt(null);
        navPathMapper.updateById(path);
        log.info("path {} deployed: {} segments", id, edgeCount);
        return NavPathVO.from(path);
    }

    /** 删除路线（边随 FK 级联删除；在途任务由 MoveTaskService 自行校验）。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        NavPath path = findOrThrow(id);
        navPathMapper.deleteById(path.getId());
    }

    /** 载入某路线的执行计划（边 + 起终点实体，供 FOLLOW_PATH 任务使用）。 */
    public List<PathEdgeVO> loadEdgeVOs(Long pathId) {
        List<NavPathEdge> edges = navPathEdgeMapper.selectList(
                Wrappers.lambdaQuery(NavPathEdge.class)
                        .eq(NavPathEdge::getPathId, pathId)
                        .orderByAsc(NavPathEdge::getSeq));
        if (edges.isEmpty()) {
            return List.of();
        }
        List<Long> pointIds = edges.stream()
                .flatMap(edge -> java.util.stream.Stream.of(edge.getSourcePointId(), edge.getTargetPointId()))
                .distinct()
                .toList();
        Map<Long, NavPoint> points = navPointMapper.selectBatchIds(pointIds).stream()
                .collect(Collectors.toMap(NavPoint::getId, Function.identity()));
        List<PathEdgeVO> result = new ArrayList<>(edges.size());
        for (NavPathEdge edge : edges) {
            NavPoint source = points.get(edge.getSourcePointId());
            NavPoint target = points.get(edge.getTargetPointId());
            if (source == null || target == null) {
                throw new IllegalStateException("路线边引用的点位不存在（edge id=" + edge.getId() + "），数据不一致");
            }
            result.add(new PathEdgeVO(edge.getId(), edge.getSeq(),
                    edge.getSourcePointId(), source.getPointCode(),
                    edge.getTargetPointId(), target.getPointCode(),
                    edge.getEdgeType(), readControlPoints(edge.getControlPoints()),
                    edge.getMaxSpeed(), edge.getBackUp(), edge.getReverse()));
        }
        return result;
    }

    public NavPath requirePath(Long id) {
        return findOrThrow(id);
    }

    /**
     * 边序列静态校验（可单测）：
     * 1. 起终点必须是本地图已有点位且不相等；
     * 2. STRAIGHT 不得带控制点，CURVE 必须带 1~2 个（桥接节点限制，超 2 个会退化直线）；
     * 3. 第 i 条边的 source 必须等于第 i-1 条边的 target（连续性）。
     */
    static void validateEdgeSequence(List<PathEdgeDTO> edges, Map<Long, NavPoint> pointsOfMap) {
        Long previousTarget = null;
        int index = 0;
        for (PathEdgeDTO edge : edges) {
            index++;
            NavPoint source = pointsOfMap.get(edge.sourcePointId());
            NavPoint target = pointsOfMap.get(edge.targetPointId());
            if (source == null) {
                throw new IllegalArgumentException("第 " + index + " 条边的 source_point_id=" + edge.sourcePointId()
                        + " 不属于该地图或不存在");
            }
            if (target == null) {
                throw new IllegalArgumentException("第 " + index + " 条边的 target_point_id=" + edge.targetPointId()
                        + " 不属于该地图或不存在");
            }
            if (edge.sourcePointId().equals(edge.targetPointId())) {
                throw new IllegalArgumentException("第 " + index + " 条边起点终点不能是同一点位");
            }
            if (previousTarget != null && !edge.sourcePointId().equals(previousTarget)) {
                throw new IllegalArgumentException("第 " + index + " 条边不连续：source 应为第 " + (index - 1)
                        + " 条边的 target（" + previousTarget + "）");
            }
            String edgeType = StringUtils.hasText(edge.edgeType()) ? edge.edgeType() : NavPathEdge.TYPE_STRAIGHT;
            int controlCount = edge.controlPoints() == null ? 0 : edge.controlPoints().size();
            if (NavPathEdge.TYPE_CURVE.equals(edgeType) && (controlCount < 1 || controlCount > 2)) {
                throw new IllegalArgumentException("第 " + index + " 条边为 CURVE，控制点必须 1~2 个，实际 "
                        + controlCount);
            }
            if (NavPathEdge.TYPE_STRAIGHT.equals(edgeType) && controlCount > 0) {
                throw new IllegalArgumentException("第 " + index + " 条边为 STRAIGHT，不能配置控制点");
            }
            previousTarget = edge.targetPointId();
        }
    }

    private static String writeControlPoints(List<ControlPointDTO> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(points);
        } catch (Exception e) {
            throw new IllegalArgumentException("控制点序列化失败: " + e.getMessage());
        }
    }

    private static List<ControlPointVO> readControlPoints(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, CONTROL_POINTS_TYPE).stream()
                    .map(point -> new ControlPointVO(point.x(), point.y()))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("控制点解析失败: " + e.getMessage(), e);
        }
    }

    private NavPath findOrThrow(Long id) {
        NavPath path = navPathMapper.selectById(id);
        if (path == null) {
            throw new NotFoundException("路线 " + id + " 不存在");
        }
        return path;
    }
}
