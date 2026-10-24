package com.agv.navdeployer.metadata.service;

import com.agv.navdeployer.exception.NotFoundException;
import com.agv.navdeployer.metadata.dto.PathSaveDTO;
import com.agv.navdeployer.metadata.dto.PathSegmentDTO;
import com.agv.navdeployer.metadata.entity.MapFloor;
import com.agv.navdeployer.metadata.entity.MapPath;
import com.agv.navdeployer.metadata.entity.MapPathSegment;
import com.agv.navdeployer.metadata.entity.MapWaypoint;
import com.agv.navdeployer.metadata.mapper.MapFloorMapper;
import com.agv.navdeployer.metadata.mapper.MapPathMapper;
import com.agv.navdeployer.metadata.mapper.MapPathSegmentMapper;
import com.agv.navdeployer.metadata.mapper.MapWaypointMapper;
import com.agv.navdeployer.metadata.vo.PathV2VO;
import com.agv.navdeployer.metadata.vo.SegmentVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 路线管理（v2）：有序途经点位链 + 段表。
 * 段表合并旧 nav_path_edge 的曲线能力（STRAIGHT/CURVE + 1~2 个贝塞尔控制点 +
 * backUp/reverse/段级限速）；segments 缺省时按相邻点位自动生成直线段。
 */
@Service
public class MapPathService {

    private static final Set<String> SUPPORTED_STATUS = Set.of(
            MapPath.STATUS_DRAFT, MapPath.STATUS_DEPLOYED, MapPath.STATUS_DISABLED);

    private final MapPathMapper pathMapper;
    private final MapPathSegmentMapper segmentMapper;
    private final MapWaypointMapper waypointMapper;
    private final MapFloorMapper floorMapper;
    private final MapVersionTicker ticker;

    public MapPathService(MapPathMapper pathMapper,
                          MapPathSegmentMapper segmentMapper,
                          MapWaypointMapper waypointMapper,
                          MapFloorMapper floorMapper,
                          MapVersionTicker ticker) {
        this.pathMapper = pathMapper;
        this.segmentMapper = segmentMapper;
        this.waypointMapper = waypointMapper;
        this.floorMapper = floorMapper;
        this.ticker = ticker;
    }

    @Transactional(rollbackFor = Exception.class)
    public PathV2VO create(PathSaveDTO dto) {
        if (dto.mapId() == null || dto.floorId() == null) {
            throw new IllegalArgumentException("新建路线必须携带 mapId 与 floorId");
        }
        if (dto.pathCode() == null || dto.pathCode().isBlank()) {
            throw new IllegalArgumentException("新建路线必须携带 pathCode");
        }
        MapFloor floor = floorMapper.selectById(dto.floorId());
        if (floor == null || !floor.getMapId().equals(dto.mapId())) {
            throw new IllegalArgumentException("楼层 " + dto.floorId() + " 不属于地图 " + dto.mapId());
        }
        String code = dto.pathCode().trim();
        if (pathMapper.selectCount(Wrappers.lambdaQuery(MapPath.class)
                .eq(MapPath::getMapId, dto.mapId())
                .eq(MapPath::getPathCode, code)) > 0) {
            throw new IllegalStateException("路线编码已存在: " + code);
        }
        MapPath path = new MapPath();
        path.setMapId(dto.mapId());
        path.setFloorId(dto.floorId());
        path.setPathCode(code);
        applyAndInsertSegments(path, dto);
        ticker.touch(dto.mapId(), dto.modifiedBy());
        return get(path.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public PathV2VO update(Long id, PathSaveDTO dto) {
        MapPath path = requirePath(id);
        if (dto.name() != null) {
            path.setName(dto.name().trim());
        }
        if (dto.isBidirectional() != null) {
            path.setIsBidirectional(dto.isBidirectional());
        }
        if (dto.maxSpeed() != null) {
            path.setMaxSpeed(dto.maxSpeed());
        }
        if (dto.enableObstacleAvoidance() != null) {
            path.setEnableObstacleAvoidance(dto.enableObstacleAvoidance());
        }
        if (dto.status() != null) {
            path.setStatus(normalizeStatus(dto.status()));
        }
        path.setUpdatedAt(null);
        if (dto.waypointIds() != null && !dto.waypointIds().isEmpty()) {
            // 点位链变化：重算起终点并整体替换段
            segmentMapper.delete(Wrappers.lambdaQuery(MapPathSegment.class)
                    .eq(MapPathSegment::getPathId, id));
            applyAndInsertSegments(path, dto);
        } else {
            pathMapper.updateById(path);
        }
        ticker.touch(path.getMapId(), dto.modifiedBy());
        return get(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        MapPath path = requirePath(id);
        pathMapper.deleteById(id); // 段级联删除
        ticker.touch(path.getMapId(), null);
    }

    public PathV2VO get(Long id) {
        MapPath path = requirePath(id);
        return PathV2VO.from(path, loadSegmentVOs(id));
    }

    public List<PathV2VO> list(Long mapId) {
        return assemble(pathMapper.selectList(Wrappers.lambdaQuery(MapPath.class)
                .eq(MapPath::getMapId, mapId).orderByAsc(MapPath::getId)));
    }

    public List<PathV2VO> listByFloor(Long floorId) {
        return assemble(pathMapper.selectList(Wrappers.lambdaQuery(MapPath.class)
                .eq(MapPath::getFloorId, floorId).orderByAsc(MapPath::getId)));
    }

    private List<PathV2VO> assemble(List<MapPath> paths) {
        if (paths.isEmpty()) {
            return List.of();
        }
        List<PathV2VO> result = new ArrayList<>(paths.size());
        for (MapPath path : paths) {
            result.add(PathV2VO.from(path, loadSegmentVOs(path.getId())));
        }
        return result;
    }

    public List<SegmentVO> loadSegmentVOs(Long pathId) {
        return segmentMapper.selectList(Wrappers.lambdaQuery(MapPathSegment.class)
                        .eq(MapPathSegment::getPathId, pathId)
                        .orderByAsc(MapPathSegment::getSeq))
                .stream().map(SegmentVO::from).toList();
    }

    /** 校验点位链与段，设置起终点并落库（path 需已插入或有 id）。 */
    private void applyAndInsertSegments(MapPath path, PathSaveDTO dto) {
        if (dto.waypointIds() == null || dto.waypointIds().size() < 2) {
            throw new IllegalArgumentException("路线至少需要 2 个途经点位");
        }
        List<Long> chain = dto.waypointIds().stream().map(Long::valueOf).toList();
        // 所有点位必须存在、属于同一地图且位于路线所在楼层
        java.util.LinkedHashMap<Long, MapWaypoint> resolved = new java.util.LinkedHashMap<>();
        for (Long waypointId : chain) {
            MapWaypoint waypoint = waypointMapper.selectById(waypointId);
            if (waypoint == null || !waypoint.getMapId().equals(path.getMapId())) {
                throw new IllegalArgumentException("点位 " + waypointId + " 不存在于当前地图");
            }
            if (!waypoint.getFloorId().equals(path.getFloorId())) {
                throw new IllegalArgumentException("点位 " + waypoint.getWaypointCode()
                        + " 不在路线所在楼层，跨楼层通行请使用楼层间连接");
            }
            resolved.put(waypointId, waypoint);
        }
        path.setFromWaypointId(chain.get(0));
        path.setToWaypointId(chain.get(chain.size() - 1));
        path.setName(dto.name() == null || dto.name().isBlank()
                ? path.getPathCode() : dto.name().trim());
        path.setIsBidirectional(dto.isBidirectional() != null ? dto.isBidirectional() : true);
        path.setMaxSpeed(dto.maxSpeed());
        path.setEnableObstacleAvoidance(dto.enableObstacleAvoidance() != null
                ? dto.enableObstacleAvoidance() : true);
        path.setStatus(dto.status() != null ? normalizeStatus(dto.status()) : MapPath.STATUS_DRAFT);
        if (path.getId() == null) {
            pathMapper.insert(path);
        } else {
            pathMapper.updateById(path);
        }

        List<PathSegmentDTO> segmentDtos = dto.segments();
        if (segmentDtos == null || segmentDtos.isEmpty()) {
            // 缺省：相邻点位自动生成直线段
            segmentDtos = new ArrayList<>(chain.size() - 1);
            for (int i = 0; i + 1 < chain.size(); i++) {
                segmentDtos.add(new PathSegmentDTO(chain.get(i), chain.get(i + 1),
                        MapPathSegment.TYPE_STRAIGHT, null, null, null, null));
            }
        }
        if (segmentDtos.size() != chain.size() - 1) {
            throw new IllegalArgumentException("段数量（" + segmentDtos.size() + "）必须等于点位链相邻对数（"
                    + (chain.size() - 1) + "）");
        }
        int seq = 1;
        for (PathSegmentDTO segmentDto : segmentDtos) {
            Long expectedSource = chain.get(seq - 1);
            Long expectedTarget = chain.get(seq);
            if (!expectedSource.equals(segmentDto.sourceWaypointId())
                    || !expectedTarget.equals(segmentDto.targetWaypointId())) {
                throw new IllegalArgumentException("第 " + seq + " 段（"
                        + codeOf(resolved, segmentDto.sourceWaypointId()) + "→"
                        + codeOf(resolved, segmentDto.targetWaypointId())
                        + "）与点位链不连续，期望 " + codeOf(resolved, expectedSource)
                        + "→" + codeOf(resolved, expectedTarget));
            }
            MapPathSegment segment = new MapPathSegment();
            segment.setPathId(path.getId());
            segment.setSeq(seq);
            segment.setSourceWaypointId(segmentDto.sourceWaypointId());
            segment.setTargetWaypointId(segmentDto.targetWaypointId());
            String type = normalizeSegmentType(segmentDto.segmentType());
            if (MapPathSegment.TYPE_CURVE.equals(type)) {
                List<double[]> controlPoints = segmentDto.controlPoints();
                if (controlPoints == null || controlPoints.isEmpty() || controlPoints.size() > 2) {
                    throw new IllegalArgumentException("第 " + seq
                            + " 段为 CURVE，必须携带 1~2 个贝塞尔控制点");
                }
                for (double[] controlPoint : controlPoints) {
                    if (controlPoint == null || controlPoint.length != 2) {
                        throw new IllegalArgumentException("控制点必须是 [x,y] 二维坐标");
                    }
                }
                segment.setControlPoints(MapZoneService.toJson(controlPoints));
            } else if (segmentDto.controlPoints() != null && !segmentDto.controlPoints().isEmpty()) {
                throw new IllegalArgumentException("第 " + seq + " 段为 STRAIGHT，不得携带控制点");
            }
            segment.setSegmentType(type);
            segment.setMaxSpeed(segmentDto.maxSpeed());
            segment.setBackUp(Boolean.TRUE.equals(segmentDto.backUp()));
            segment.setReverse(Boolean.TRUE.equals(segmentDto.reverse()));
            segmentMapper.insert(segment);
            seq++;
        }
    }

    private MapPath requirePath(Long id) {
        MapPath path = pathMapper.selectById(id);
        if (path == null) {
            throw new NotFoundException("路线 " + id + " 不存在");
        }
        return path;
    }

    private static String normalizeSegmentType(String type) {
        if (type == null || type.isBlank() || MapPathSegment.TYPE_STRAIGHT.equalsIgnoreCase(type.trim())) {
            return MapPathSegment.TYPE_STRAIGHT;
        }
        if (MapPathSegment.TYPE_CURVE.equalsIgnoreCase(type.trim())) {
            return MapPathSegment.TYPE_CURVE;
        }
        throw new IllegalArgumentException("不支持的段类型: " + type + "（可选 STRAIGHT/CURVE）");
    }

    private static String normalizeStatus(String status) {
        for (String supported : SUPPORTED_STATUS) {
            if (supported.equalsIgnoreCase(status.trim())) {
                return supported;
            }
        }
        throw new IllegalArgumentException("不支持的路线状态: " + status + "（可选 DRAFT/DEPLOYED/DISABLED）");
    }

    private static String codeOf(java.util.Map<Long, MapWaypoint> resolved, Long id) {
        MapWaypoint waypoint = resolved.get(id);
        return waypoint != null ? waypoint.getWaypointCode() : String.valueOf(id);
    }
}
